package com.cv.gaussianslplatting.backend.controller;


import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileInputStream;


@RestController
@RequestMapping("/api")
public class MediaController {

    @Value("${media.upload.path}") // 在application.properties中配置
    private String uploadPath;

    @PostMapping("/upload-and-render")
    public ResponseEntity<?> uploadAndRender(@RequestParam("file") MultipartFile file) {
        try {
            // 检查文件是否为空
            if (file.isEmpty()) {
                return ResponseEntity.badRequest().body("请选择文件进行上传");
            }

            // 获取文件名
            String fileName = file.getOriginalFilename();
            File uploadDir = new File(uploadPath);
            if (!uploadDir.exists()) {
                uploadDir.mkdirs();
            }

            // 保存上传的源文件
            File dest = new File(uploadPath + File.separator + fileName);
            file.transferTo(dest);

            // 渲染处理，返回生成的 PLY 文件路径
            String plyFilePath = executeGaussianRendering(dest.getAbsolutePath());
            File plyFile = new File(plyFilePath);

            // 检查 PLY 文件是否生成
            if (!plyFile.exists()) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body("渲染成功，但生成的 PLY 文件不存在");
            }

            // 准备返回文件流
            InputStreamResource resource = new InputStreamResource(new FileInputStream(plyFile));

            // 设置 HTTP 响应头，返回文件
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

    private String executeGaussianRendering(String filePath) throws Exception {
        try {
            // 构建渲染命令
            String command = String.format("python train.py -s %s --iterations 3000", filePath);

            // 使用 ProcessBuilder 启动进程
            ProcessBuilder processBuilder = new ProcessBuilder();
            if (System.getProperty("os.name").toLowerCase().startsWith("windows")) {
                processBuilder.command("cmd.exe", "/c", command);
            } else {
                processBuilder.command("bash", "-c", command);
            }

            Process process = processBuilder.start();

            // 等待进程结束
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new RuntimeException("渲染进程退出代码: " + exitCode);
            }

            // 假设生成的 PLY 文件与输入文件在相同目录下，文件名为渲染完成后的固定命名：`output.ply`
            // 可以根据 `train.py` 实际逻辑调整文件路径
            return filePath.replace(filePath.substring(filePath.lastIndexOf('.') + 1), "ply");

        } catch (Exception e) {
            throw new Exception("渲染命令执行失败: " + e.getMessage());
        }
    }
}
