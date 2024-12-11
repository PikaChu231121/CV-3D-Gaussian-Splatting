package com.cv.gaussianslplatting.backend.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api")
public class MediaController {
    @Value("${media.upload.path}")
    private String baseUploadPath;

    @Value("${gaussian.splatting.path}")
    private String gaussianSplattingPath;

    @Value("${anaconda.path}")
    private String anacondaPath;

    @PostMapping("/upload-and-render")
    public ResponseEntity<?> uploadAndRender(@RequestParam("file") MultipartFile file) {
        try {
            if (file.isEmpty()) {
                return ResponseEntity.badRequest().body("请选择文件进行上传");
            }

            // 创建工作目录结构
            String uniqueSubFolder = UUID.randomUUID().toString();
            Path workDir = Paths.get(baseUploadPath, uniqueSubFolder);
            Path inputDir = workDir.resolve("input");
            Path outputDir = workDir.resolve("output");
            Path colmapDir = workDir.resolve("colmap_data");

            // 创建所需的目录
            Files.createDirectories(workDir);
            Files.createDirectories(inputDir);
            Files.createDirectories(outputDir);
            Files.createDirectories(colmapDir);

            // 保存上传的视频文件
            String videoFileName = "input.mp4";
            Path videoPath = workDir.resolve(videoFileName);
            file.transferTo(videoPath.toFile());

            // 执行处理流程
            String plyFilePath = executeGaussianRendering(workDir.toString(), colmapDir.toString());
            File plyFile = new File(plyFilePath);

            if (!plyFile.exists()) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body("渲染失败，PLY文件不存在");
            }

            // 返回处理结果
            InputStreamResource resource = new InputStreamResource(new FileInputStream(plyFile));
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + plyFile.getName())
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .contentLength(plyFile.length())
                    .body(resource);

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("处理失败: " + e.getMessage());
        }
    }

    private String executeGaussianRendering(String workDir, String colmapDir) throws Exception {
        try {
            // 1. 视频转图片帧
            String ffmpegCommand = String.format(
                    "ffmpeg -i %s/input.mp4 -vf \"setpts=0.2*PTS\" %s/input/input_%%4d.jpg",
                    workDir, workDir
            );
            executeCommand(ffmpegCommand, workDir);

            // 2. 使用colmap转换点图
            String condaInitCommand = String.format(
                    "export PATH=%s/bin:$PATH && . %s/etc/profile.d/conda.sh && conda activate gaussian_splatting && ",
                    anacondaPath, anacondaPath
            );

            // 使用 -s 参数指定源目录
            String convertCommand = condaInitCommand + String.format(
                    "cd %s && python convert.py --source_path %s",
                    gaussianSplattingPath, workDir
            );
            executeCommand(convertCommand, workDir);

            // 3. 训练模型
            String trainCommand = condaInitCommand + String.format(
                    "cd %s && python train.py -s %s -m %s/output --iterations 1000",
                    gaussianSplattingPath, workDir, workDir
            );
            executeCommand(trainCommand, workDir);

            return Paths.get(workDir, "output", "point_cloud", "iteration_1000", "point_cloud.ply").toString();

        } catch (Exception e) {
            throw new Exception("处理失败: " + e.getMessage());
        } finally {
            cleanupColmapFiles(colmapDir);
        }
    }

    private void executeCommand(String command, String workDir) throws Exception {
        ProcessBuilder processBuilder = new ProcessBuilder();
        processBuilder.directory(new File(workDir));

        // 设置环境变量
        Map<String, String> env = processBuilder.environment();
        env.put("PATH", anacondaPath + "/bin:" + env.get("PATH"));

        if (System.getProperty("os.name").toLowerCase().startsWith("windows")) {
            processBuilder.command("cmd.exe", "/c", command);
        } else {
            processBuilder.command("bash", "-c", command);
        }

        processBuilder.redirectErrorStream(true);

        Process process = processBuilder.start();

        // 读取并记录输出
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        String line;
        StringBuilder output = new StringBuilder();
        while ((line = reader.readLine()) != null) {
            System.out.println(line);
            output.append(line).append("\n");
        }

        // 设置超时时间
        if (!process.waitFor(30, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new Exception("处理超时");
        }

        if (process.exitValue() != 0) {
            throw new RuntimeException("命令执行失败: \n" + output);
        }
    }

    private void cleanupColmapFiles(String colmapDir) {
        try {
            // 保留必要的文件，删除临时文件
            File colmapFolder = new File(colmapDir);
            if (colmapFolder.exists()) {
                File[] files = colmapFolder.listFiles();
                if (files != null) {
                    for (File file : files) {
                        if (!file.getName().equals("database.db")) {
                            file.delete();
                        }
                    }
                }
            }
        } catch (Exception e) {
            // 记录清理错误但不抛出异常
            System.err.println("清理COLMAP文件时发生错误: " + e.getMessage());
        }
    }
}

