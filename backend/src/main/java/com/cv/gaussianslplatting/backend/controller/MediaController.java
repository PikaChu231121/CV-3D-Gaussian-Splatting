package com.cv.gaussianslplatting.backend.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*") // 允许所有来源

public class MediaController {
    @Value("${server.url}")  // 添加服务器 URL 配置
    private String serverUrl;

    @Value("${media.upload.path}")
    private String baseUploadPath;

    @Value("${gaussian.splatting.path}")
    private String gaussianSplattingPath;

    @Value("${anaconda.path}")
    private String anacondaPath;

    @PostMapping("/upload-video-and-render")
    public ResponseEntity<?> uploadVideoAndRender(@RequestParam("file") MultipartFile file) {
        try {
            if (file.isEmpty()) {
                return ResponseEntity.badRequest().body("请选择文件进行上传");
            }

            WorkDirectory workDir = createWorkDirectory();
            // 保存视频文件
            Path videoPath = workDir.basePath().resolve("input.mp4");
            file.transferTo(videoPath.toFile());

            return processAndReturnResult(workDir, true);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("处理失败: " + e.getMessage());
        }
    }

    @PostMapping("/upload-images-and-render")
    public ResponseEntity<?> uploadImagesAndRender(@RequestParam("files") MultipartFile[] files) {
        try {
            if (files == null || files.length == 0) {
                return ResponseEntity.badRequest().body("请选择图片文件进行上传");
            }

            if (!validateImages(files)) {
                return ResponseEntity.badRequest().body("只支持图片文件格式");
            }

            WorkDirectory workDir = createWorkDirectory();
            // 保存图片文件
            saveImages(files, workDir.inputDir());

            return processAndReturnResult(workDir, false);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("处理失败: " + e.getMessage());
        }
    }

    private ResponseEntity<?> processAndReturnResult(WorkDirectory workDir, boolean isVideo) throws Exception {
        try {
            if (isVideo) {
                // 视频转换为图片帧
                executeCommand(String.format(
                        "ffmpeg -i %s/input.mp4 -vf \"setpts=0.2*PTS\" %s/input/input_%%4d.jpg",
                        workDir.basePath(), workDir.basePath()
                ), workDir.basePath().toString());
            }

            // 执行渲染流程
            executeGaussianRendering(workDir);

            cleanupFiles(workDir.basePath().toString());
            // 返回结果
            File plyFile = workDir.basePath().resolve("result/point_cloud.ply").toFile();
            if (!plyFile.exists()) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body("渲染失败，PLY文件不存在");
            }

            // 生成并返回 URL
            String fileUrl = generateFileUrl(workDir.basePath().getFileName().toString(), "result/point_cloud.ply");
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("url", fileUrl));

        } catch (Exception e) {
            cleanupFiles(workDir.basePath().toString());
            throw e;
        }
    }

    private String generateFileUrl(String workDirName, String relativePath) {
        return String.format("%s/api/files/%s/%s", serverUrl, workDirName, relativePath);
    }

    //添加新的文件访问接口
    @GetMapping("/files/{workDir}/**")
    public ResponseEntity<?> getFile(@PathVariable String workDir, HttpServletRequest request) {
        try {
            // 获取文件相对路径
            String relativePath = request.getRequestURI()
                    .split("/files/" + workDir + "/")[1];

            // 构建完整文件路径
            Path filePath = Paths.get(baseUploadPath, workDir, relativePath);
            File file = filePath.toFile();

            if (!file.exists()) {
                return ResponseEntity.notFound().build();
            }

            // 返回文件
            InputStreamResource resource = new InputStreamResource(new FileInputStream(file));
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + file.getName())
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .contentLength(file.length())
                    .body(resource);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("文件访问失败: " + e.getMessage());
        }
    }

    private void executeGaussianRendering(WorkDirectory workDir) throws Exception {
        String condaInitCommand = String.format(
                "export PATH=%s/bin:$PATH && . %s/etc/profile.d/conda.sh && conda activate gaussian_splatting && ",
                anacondaPath, anacondaPath
        );

        // Convert
        executeCommand(condaInitCommand + String.format(
                "cd %s && python convert.py --source_path %s",
                gaussianSplattingPath, workDir.basePath()
        ), workDir.basePath().toString());

        // Train
        executeCommand(condaInitCommand + String.format(
                "cd %s && python train.py -s %s -m %s/output --iterations 1000",
                gaussianSplattingPath, workDir.basePath(), workDir.basePath()
        ), workDir.basePath().toString());
    }

    // 记录类
    private record WorkDirectory(Path basePath, Path inputDir, Path outputDir, Path colmapDir) {}

    private WorkDirectory createWorkDirectory() throws IOException {
        String uniqueSubFolder = UUID.randomUUID().toString();
        Path basePath = Paths.get(baseUploadPath, uniqueSubFolder);
        Path inputDir = basePath.resolve("input");
        Path outputDir = basePath.resolve("output");
        Path colmapDir = basePath.resolve("colmap_data");

        Files.createDirectories(basePath);
        Files.createDirectories(inputDir);
        Files.createDirectories(outputDir);
        Files.createDirectories(colmapDir);

        return new WorkDirectory(basePath, inputDir, outputDir, colmapDir);
    }

    private boolean validateImages(MultipartFile[] files) {
        return Arrays.stream(files)
                .allMatch(file -> file.getContentType() != null && file.getContentType().startsWith("image/"));
    }

    private void saveImages(MultipartFile[] files, Path inputDir) throws IOException {
        for (int i = 0; i < files.length; i++) {
            String fileName = String.format("input_%04d.jpg", i + 1);
            files[i].transferTo(inputDir.resolve(fileName));
        }
    }

    private void cleanupFiles(String workDir) throws IOException {
        Path workDirPath = Paths.get(workDir);

        // 要保留的文件和目录
        Set<String> keepFiles = new HashSet<>(Arrays.asList(
                "input.mp4",  // 原始输入视频
                "point_cloud.ply",  // 最终输出的PLY文件
                "input"  // 图片帧
        ));

        // 递归删除不需要的文件
        Files.walk(workDirPath)
                .sorted(Comparator.reverseOrder()) // 确保先处理文件，后处理目录
                .forEach(path -> {
                    try {
                        String relativePath = workDirPath.relativize(path).toString();

                        // 跳过工作目录本身
                        if (path.equals(workDirPath)) {
                            return;
                        }

                        // 检查是否是需要保留的文件或目录
                        boolean shouldKeep =
                                // 保留指定文件
                                keepFiles.contains(path.getFileName().toString()) ||
                                        // 保留指定目录中的图片帧
                                        (relativePath.startsWith("input/") && path.getFileName().toString()
                                                .matches("input_\\d+\\.jpg")) ||
                                        // 保留最终输出目录
                                        relativePath.startsWith("output/point_cloud/iteration_1000");

                        if (!shouldKeep) {
                            Files.deleteIfExists(path);
                            System.out.println("已删除: " + path);
                        }
                    } catch (IOException e) {
                        System.err.println("删除文件失败: " + path + ", 错误: " + e.getMessage());
                    }
                });

        // 创建精简的输出结构
        Path finalOutputDir = workDirPath.resolve("result");
        Files.createDirectories(finalOutputDir);

        // 移动PLY文件到最终位置
        Path sourcePlyPath = workDirPath.resolve("output/point_cloud/iteration_1000/point_cloud.ply");
        Path targetPlyPath = finalOutputDir.resolve("point_cloud.ply");
        if (Files.exists(sourcePlyPath)) {
            Files.move(sourcePlyPath, targetPlyPath, StandardCopyOption.REPLACE_EXISTING);
        }

        // 删除空目录，但保留必要的目录
        Files.walk(workDirPath)
                .sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        if (Files.isDirectory(path) &&
                                !path.equals(workDirPath) &&
                                !path.getFileName().toString().equals("input") &&
                                !path.getFileName().toString().equals("result") &&
                                isDirEmpty(path)) {
                            Files.delete(path);
                            System.out.println("已删除空目录: " + path);
                        }
                    } catch (IOException e) {
                        System.err.println("删除目录失败: " + path + ", 错误: " + e.getMessage());
                    }
                });
    }


    private boolean isDirEmpty(Path directory) throws IOException {
        try (DirectoryStream<Path> dirStream = Files.newDirectoryStream(directory)) {
            return !dirStream.iterator().hasNext();
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

}
