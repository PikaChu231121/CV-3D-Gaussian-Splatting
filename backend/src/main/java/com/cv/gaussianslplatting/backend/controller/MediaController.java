package com.cv.gaussianslplatting.backend.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class MediaController {
    // 上传根目录  
    @Value("${media.upload.path}")
    private String baseUploadPath;

    @PostMapping("/upload-and-render")
    public ResponseEntity<?> uploadAndRender(@RequestParam("file") MultipartFile file) {
        try {
            // 检查文件是否为空  
            if (file.isEmpty()) {
                return ResponseEntity.badRequest().body("请选择文件进行上传");
            }

            // 动态生成一个唯一子文件夹  
            String uniqueSubFolder = UUID.randomUUID().toString();
            Path uploadDir = Paths.get(baseUploadPath, uniqueSubFolder);
            Files.createDirectories(uploadDir); // 创建子文件夹  

            // 将文件保存到子文件夹中  
            String fileName = StringUtils.cleanPath(Objects.requireNonNull(file.getOriginalFilename()));
            Path filePath = uploadDir.resolve(fileName);
            file.transferTo(filePath.toFile());

            // 渲染处理，返回生成的 PLY 文件路径  
            String plyFilePath = executeGaussianRendering(uploadDir.toString());
            File plyFile = new File(plyFilePath);

            // 检查生成的 PLY 文件是否存在  
            if (!plyFile.exists()) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body("渲染成功，但生成的 PLY 文件不存在");
            }

            // 准备返回文件流  
            InputStreamResource resource = new InputStreamResource(new FileInputStream(plyFile));

            // 设置 HTTP 响应，返回文件  
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + plyFile.getName())
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .contentLength(plyFile.length())
                    .body(resource);

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("文件上传或渲染失败: " + e.getMessage());
        }
    }

    private String executeGaussianRendering(String folderPath) throws Exception {
        try {
            // 构建渲染命令，将子文件夹路径作为输入路径  
            String command = String.format("python train.py -s %s --iterations 3000", folderPath);

            // 使用 ProcessBuilder 启动进程  
            ProcessBuilder processBuilder = new ProcessBuilder();
            if (System.getProperty("os.name").toLowerCase().startsWith("windows")) {
                processBuilder.command("cmd.exe", "/c", command);
            } else {
                processBuilder.command("bash", "-c", command);
            }

            Process process = processBuilder.start();

            // 输出渲染日志到控制台（可选）  
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream())
            );
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(line);
            }

            // 等待进程完成  
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new RuntimeException("渲染进程退出代码: " + exitCode);
            }

            // 假设生成的 PLY 文件位于输入子文件夹中，名称为固定的 output.ply  
            return Paths.get(folderPath, "output.ply").toString();

        } catch (Exception e) {
            throw new Exception("渲染命令执行失败: " + e.getMessage());
        }
    }
}