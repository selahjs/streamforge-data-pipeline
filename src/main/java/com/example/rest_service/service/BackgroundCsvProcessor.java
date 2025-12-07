// src/main/java/com/example/rest_service/service/BackgroundCsvProcessor.java
package com.example.rest_service.service;

import com.example.rest_service.model.Item;
import com.example.rest_service.repository.ItemRepository;
import com.univocity.parsers.csv.CsvParser;
import com.univocity.parsers.csv.CsvParserSettings;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import java.io.UncheckedIOException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static com.example.rest_service.service.CsvUploadService.*;

@Component
public class BackgroundCsvProcessor {

  // Removed: private final CsvUploadService parentService;
  private final ItemRepository itemRepository;
  private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

  // Constructor is clean, only injecting necessary repository
  public BackgroundCsvProcessor(ItemRepository itemRepository) {
    this.itemRepository = itemRepository;
  }

  /**
   * Helper to update the shared jobStatus map directly (breaks circular dependency).
   */
  private void updateStatus(String jobId, Map<String, Status> jobStatus, String step, String message, long processedRows, long totalRows) {
    jobStatus.put(jobId, new Status(step, message, processedRows, totalRows));
    System.out.println(String.format("JOB[%s] STATUS: %s - %s", jobId, step, message));
  }

  /**
   * The entry point for background processing.
   * NOTE: Accepts the jobStatus map by reference to allow updates.
   */
  @Async("csvProcessorExecutor")
  public void startProcessing(File permanentFile, Mode mode, String jobId, Map<String, Status> jobStatus) {

    // 1. Optimization: Prefetch all existing IDs ONCE
    updateStatus(jobId, jobStatus, "DB_PREFETCH", "Fetching existing unique IDs from DB...", 0, 0);
    Set<String> existingExternalIds = itemRepository.findAllExternalIds();
    updateStatus(jobId, jobStatus, "PREFETCH_COMPLETE", "Unique ID prefetch complete.", 0, 0);

    try (
            // Read the File using standard Java IO
            InputStream in = new FileInputStream(permanentFile);
            InputStreamReader isr = new InputStreamReader(in);
            BufferedReader reader = new BufferedReader(isr)) {

      CsvParserSettings settings = new CsvParserSettings();
      settings.setHeaderExtractionEnabled(true);
      CsvParser parser = new CsvParser(settings);
      parser.beginParsing(reader);

      UploadResult result;

      // 2. Processing Logic
      if (mode == Mode.ALL_OR_NOTHING) {
        result = processAllOrNothing(parser, existingExternalIds, jobId, jobStatus);
      } else {
        result = processChunked(parser, 1000, existingExternalIds, jobId, jobStatus);
      }

      // 3. Final Status Update
      updateStatus(jobId, jobStatus, "JOB_COMPLETE",
              String.format("Finished. Inserted: %d, Failed: %d",
                      result.inserted(), result.failed()),
              result.processed(), result.processed());

    } catch (Exception ex) {
      updateStatus(jobId, jobStatus, "JOB_FAILED", "Processing failed: " + ex.getMessage(), 0, 0);
      ex.printStackTrace(); // Log the error
    } finally {
      // --- NEW: Delete the permanent file when done (success or failure) ---
      if (permanentFile != null && permanentFile.exists()) {
        if (permanentFile.delete()) {
          System.out.println("Cleaned up file: " + permanentFile.getAbsolutePath());
        } else {
          System.err.println("WARNING: Could not delete file: " + permanentFile.getAbsolutePath());
        }
      }
    }
  }

  // --- Core Processing Logic ---

  /**
   * ALL_OR_NOTHING: single DB transaction for the entire file.
   * NOTE: Now accepts the jobStatus map.
   */
  @Transactional
  protected UploadResult processAllOrNothing(CsvParser parser, Set<String> existingIds, String jobId, Map<String, Status> jobStatus) {
    updateStatus(jobId, jobStatus, "PROCESS_ALL_OR_NOTHING", "Accumulating all data for single transaction...", 0, 0);
    List<Item> batch = new ArrayList<>();
    long processed = 0L;
    long failed = 0L;
    Map<String, Long> errorCounts = new ConcurrentHashMap<>();
    File errorReport = createTempFile("errors_all_or_noting", ".csv");

    try (PrintWriter err = new PrintWriter(new FileWriter(errorReport))) {
      String[] row;
      while ((row = parser.parseNext()) != null) {
        processed++;
        if (processed % 5000 == 0) { // Dynamic Progress Tracking
          updateStatus(jobId, jobStatus, "PROCESSING", String.format("Accumulating rows... %d processed", processed), processed, 0);
        }

        Optional<String> validation = validateRow(row, existingIds);
        if (validation.isEmpty()) {
          batch.add(rowToEntity(row));
        } else {
          failed++;
          String errorMessage = validation.get();
          errorCounts.merge(errorMessage, 1L, Long::sum);
          err.println(String.join(",", safeArray(row)) + "," + errorMessage);
        }
      }
      updateStatus(jobId, jobStatus, "DB_COMMIT", "Attempting single database commit...", processed, 0);

      // Store all in one transaction
      itemRepository.saveAll(batch);
      itemRepository.flush();

      updateStatus(jobId, jobStatus, "DB_COMMIT_SUCCESS", "Database transaction completed successfully.", processed, processed);

      return new UploadResult(processed, batch.size(), failed, errorReport, new ValidationSummary(errorCounts));
    } catch (IOException ex) {
      updateStatus(jobId, jobStatus, "FILE_WRITE_FAILED", "Failed to write error report.", processed, 0);
      throw new UncheckedIOException("Failed to write error report", ex);
    } catch (RuntimeException ex) {
      updateStatus(jobId, jobStatus, "DB_COMMIT_FAILED", "Database transaction failed and rolled back.", processed, 0);
      throw ex;
    }
  }

  /**
   * CHUNK_COMMIT: commit per chunk to limit memory and transaction size.
   * NOTE: Now accepts the jobStatus map.
   */
  protected UploadResult processChunked(CsvParser parser, int chunkSize, Set<String> existingIds, String jobId, Map<String, Status> jobStatus) throws IOException {
    updateStatus(jobId, jobStatus, "PROCESS_CHUNK_STARTED", "Starting chunked processing...", 0, 0);
    List<Item> batch = new ArrayList<>(Math.max(16, chunkSize));
    long processed = 0L;
    long failed = 0L;
    long inserted = 0L;
    Map<String, Long> errorCounts = new ConcurrentHashMap<>();
    File errorReport = createTempFile("errors_chunked", ".csv");

    try (PrintWriter err = new PrintWriter(new FileWriter(errorReport))) {
      String[] row;
      while ((row = parser.parseNext()) != null) {
        processed++;
        // Dynamic Progress Tracking (Updates every 5000 rows)
        if (processed % 5000 == 0) {
          updateStatus(jobId, jobStatus, "PROCESSING", String.format("Processing rows... %d processed", processed), processed, 0);
        }

        Optional<String> validation = validateRow(row, existingIds);
        if (validation.isEmpty()) {
          batch.add(rowToEntity(row));
        } else {
          failed++;
          String errorMessage = validation.get();
          errorCounts.merge(errorMessage, 1L, Long::sum);
          err.println(String.join(",", safeArray(row)) + "," + errorMessage);
        }

        if (batch.size() >= chunkSize) {
          inserted += saveChunk(batch);
          batch.clear();
        }
      }
      // Final chunk commit
      if (!batch.isEmpty()) {
        inserted += saveChunk(batch);
      }

      return new UploadResult(processed, inserted, failed, errorReport, new ValidationSummary(errorCounts));
    }
  }

  /**
   * Save a chunk in its own transaction to allow partial success.
   */
  @Transactional
  protected long saveChunk(List<Item> chunk) {
    itemRepository.saveAll(chunk);
    itemRepository.flush();
    return chunk.size();
  }

  // --- Validation and Mapping Helpers ---

  /**
   * Validate a CSV row. Uses the pre-fetched Set for uniqueness check.
   */
  private Optional<String> validateRow(String[] row, Set<String> existingIds) {
    if (row == null || row.length < 4) return Optional.of("too few columns");

    String externalId = (row[0] == null) ? "" : row[0].trim();
    String name = (row[1] == null) ? "" : row[1].trim();
    String qtyStr = (row[2] == null) ? "" : row[2].trim();
    String dateStr = (row[3] == null) ? "" : row[3].trim();

    if (externalId.isEmpty()) return Optional.of("externalId empty");
    if (name.isEmpty()) return Optional.of("name empty");

    // 1. Uniqueness check using the in-memory Set (FAST)
    if (existingIds.contains(externalId)) {
      return Optional.of("duplicate externalId");
    }
    // Add ID to the set to catch duplicates within the current file as well
    existingIds.add(externalId);

    // 2. Data type checks
    try {
      Integer.parseInt(qtyStr);
    } catch (NumberFormatException ex) {
      return Optional.of("quantity invalid");
    }

    try {
      LocalDate.parse(dateStr, DATE_FMT);
    } catch (Exception ex) {
      return Optional.of("expiryDate invalid (expected yyyy-MM-dd)");
    }

    return Optional.empty();
  }

  /**
   * Convert CSV row to entity. Assumes validateRow was called first.
   */
  private Item rowToEntity(String[] row) {
    Item item = new Item();
    item.setExternalId(row[0].trim());
    item.setName(row[1].trim());
    item.setQuantity(Integer.parseInt(row[2].trim()));
    item.setExpiryDate(LocalDate.parse(row[3].trim(), DATE_FMT));
    return item;
  }

  /**
   * Helper to create a temporary error report file.
   */
  private File createTempFile(String prefix, String suffix) {
    try {
      return File.createTempFile(prefix, suffix);
    } catch (IOException e) {
      throw new UncheckedIOException("Unable to create temp file for error report", e);
    }
  }

  /**
   * Safe array-to-string helper when some cells might be null.
   */
  private String[] safeArray(String[] row) {
    if (row == null) return new String[]{};
    String[] out = new String[row.length];
    for (int i = 0; i < row.length; i++) {
      out[i] = row[i] == null ? "" : row[i].replaceAll(",", "");
    }
    return out;
  }
}