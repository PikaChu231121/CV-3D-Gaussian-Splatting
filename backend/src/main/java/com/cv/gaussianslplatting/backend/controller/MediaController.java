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
@CrossOrigin(origins = "*") // Allow all origins

public class MediaController {
    @Value("${server.url}")  // Server URL
    private String serverUrl;

    @Value("${media.upload.path}")
    private String baseUploadPath;

    @Value("${gaussian.splatting.path}")
    private String gaussianSplattingPath;

    @Value("${anaconda.path}")
    private String anacondaPath;

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MediaController.class);

    @GetMapping("/health")
    public ResponseEntity<String> healthCheck() {
        return ResponseEntity.ok("Service is running");
    }


    @PostMapping("/upload-video-and-render")
    public ResponseEntity<?> uploadVideoAndRender(@RequestParam("file") MultipartFile file) {
        log.info("Received video upload request. File name: {}, size: {}",
                file.getOriginalFilename(), file.getSize());

        try {
            if (file.isEmpty()) {
                log.warn("Empty file received");
                return ResponseEntity.badRequest().body("请选择文件进行上传");
            }

            log.info("Creating work directory...");
            WorkDirectory workDir = createWorkDirectory();

            // Save video
            log.info("Saving video file to: {}", workDir.basePath());
            Path videoPath = workDir.basePath().resolve("input.mp4");
            file.transferTo(videoPath.toFile());

            log.info("Processing video...");
            return processAndReturnResult(workDir, true);
        } catch (Exception e) {
            log.error("Error processing video upload", e);
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
            // Save images
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
                // Convert video to image frames
                executeCommand(String.format(
                        "ffmpeg -i %s/input.mp4 -vf \"setpts=0.2*PTS\" %s/input/input_%%4d.jpg",
                        workDir.basePath(), workDir.basePath()
                ), workDir.basePath().toString());
            }

            // Render
            executeGaussianRendering(workDir);

            // Return result
            File plyFile = workDir.basePath().resolve("output/point_cloud/iteration_3000/point_cloud.ply").toFile();
            if (!plyFile.exists()) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body("渲染失败，PLY文件不存在");
            }

            // Generate and return URL
            String fileUrl = generateFileUrl(workDir.basePath().getFileName().toString(), "output/point_cloud/iteration_3000/point_cloud.ply");
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("url", fileUrl));

        } catch (Exception e) {
            throw e;
        }
    }

    private String generateFileUrl(String workDirName, String relativePath) {
        return String.format("%s/api/files/%s/%s", serverUrl, workDirName, relativePath);
    }

    // File access endpoint
    @GetMapping("/files/{workDir}/**")
    public ResponseEntity<?> getFile(@PathVariable String workDir, HttpServletRequest request) {
        try {
            // Get file relative path
            String relativePath = request.getRequestURI()
                    .split("/files/" + workDir + "/")[1];

            // Build full file path
            Path filePath = Paths.get(baseUploadPath, workDir, relativePath);
            File file = filePath.toFile();

            if (!file.exists()) {
                return ResponseEntity.notFound().build();
            }

            // return the file
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
                "cd %s && python train.py -s %s -m %s/output --iterations 3000",
                gaussianSplattingPath, workDir.basePath(), workDir.basePath()
        ), workDir.basePath().toString());
    }

    // Record class
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
                .allMatch(file -> {
                    String contentType = file.getContentType();
                    return contentType != null && contentType.startsWith("image/");
                });
    }

    private void saveImages(MultipartFile[] files, Path inputDir) throws IOException {
        for (int i = 0; i < files.length; i++) {
            String fileName = String.format("input_%04d.jpg", i + 1);
            files[i].transferTo(inputDir.resolve(fileName));
        }
    }



    private void executeCommand(String command, String workDir) throws Exception {
        ProcessBuilder processBuilder = new ProcessBuilder();
        processBuilder.directory(new File(workDir));

        // set the PATH
        Map<String, String> env = processBuilder.environment();
        env.put("PATH", anacondaPath + "/bin:" + env.get("PATH"));

        if (System.getProperty("os.name").toLowerCase().startsWith("windows")) {
            processBuilder.command("cmd.exe", "/c", command);
        } else {
            processBuilder.command("bash", "-c", command);
        }

        processBuilder.redirectErrorStream(true);

        Process process = processBuilder.start();

        // Log output
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        String line;
        StringBuilder output = new StringBuilder();
        while ((line = reader.readLine()) != null) {
            System.out.println(line);
            output.append(line).append("\n");
        }

        // set timeout
        if (!process.waitFor(30, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new Exception("处理超时");
        }

        if (process.exitValue() != 0) {
            throw new RuntimeException("命令执行失败: \n" + output);
        }
    }

}
