package com.example.rest_service.controller;

import com.example.rest_service.service.CsvUploadService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/upload")
public class CsvUploadController {
  private final CsvUploadService service;

  public CsvUploadController(CsvUploadService service) {
    this.service = service;
  }

  @GetMapping("/test")
  public ResponseEntity<String> test() {
    return ResponseEntity.ok().body("test");
  }

  @PostMapping("/items")
  public ResponseEntity<?> uploadItems(@RequestParam("file") MultipartFile file,
                                       @RequestParam(value="mode", defaultValue="CHUNK_COMMIT") CsvUploadService.Mode mode) {
    try {
      var res = service.handleUpload(file, mode);
      // Return summary and a link to download error file if any (in real app store it in S3)
      var body = Map.of(
              "processed", res.processed(),
              "summary", res.summary(),
              "inserted", res.inserted(),
              "failed", res.failed(),
              "errorReport", res.errorReportFile() != null ? "generated" : null
      );
      return ResponseEntity.ok(body);
    } catch (Exception e) {
      return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
    }
  }
  // NEW: Endpoint to check the progress status
  @GetMapping("/status")
  public ResponseEntity<CsvUploadService.Status> getUploadStatus() {
    return ResponseEntity.ok(service.getStatus());
  }
}
