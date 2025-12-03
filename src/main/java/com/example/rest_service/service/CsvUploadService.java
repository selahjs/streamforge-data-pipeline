package com.example.rest_service.service;

import com.example.rest_service.model.Item;
import com.example.rest_service.repository.ItemRepository;
import com.univocity.parsers.csv.CsvParser;
import com.univocity.parsers.csv.CsvParserSettings;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CsvUploadService
 *
 * - handleUpload(...) is the entry point
 * - Two modes: ALL_OR_NOTHING (single transaction) and CHUNK_COMMIT (commits per chunk)
 *
 * Note: validateRow currently calls DB for uniqueness check. For large files consider
 * prefetching existing externalIds or rely on unique constraint and handle duplicate exceptions.
 */
@Service
public class CsvUploadService {

  private final JdbcTemplate jdbcTemplate;      // optional: for faster batch inserts
  private final ItemRepository itemRepository;
  private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

  public CsvUploadService(JdbcTemplate jdbcTemplate, ItemRepository itemRepository) {
    this.jdbcTemplate = jdbcTemplate;
    this.itemRepository = itemRepository;
  }
  // New: Map to hold the status of the current (or last) running job
  private final Map<String, Status> jobStatus = new ConcurrentHashMap<>();

  // New: Status record to hold the progress information
  public record Status(String message, String step) {} // <-- New record

  // New: Public method for the controller to get the status
  public Status getStatus() {
    return jobStatus.getOrDefault("currentJob", new Status("Not Started", "START"));
  }

  // New: Helper to update the status map
  private void updateStatus(String step, String message) {
    // Using a fixed key "currentJob" for simplicity
    jobStatus.put("currentJob", new Status(message, step));
    System.out.println("STATUS UPDATE: " + step + " - " + message);
  }

  /**
   * Holds a summary of validation errors encountered, mapping the error message
   * (e.g., "quantity invalid") to the count of records that failed for that reason.
   */
  public record ValidationSummary(Map<String, Long> errorCounts) {}

  /**
   * The result of the upload process, now including the ValidationSummary.
   */
  public record UploadResult(long processed, long inserted, long failed, File errorReportFile, ValidationSummary summary) {}

  public enum Mode { ALL_OR_NOTHING, CHUNK_COMMIT }

  /**
   * Public entry point.
   */
  public UploadResult handleUpload(MultipartFile file, Mode mode) throws Exception {
    updateStatus("FILE_RECEIVED", "File successfully uploaded to server's temporary storage."); // <-- Status Update 1
    // stream the file; do NOT read fully into memory
//    InputStream reads raw binary data
    try (
            InputStream in = file.getInputStream();
         //InputStreamReader converts raw binary to text characters
         InputStreamReader isr = new InputStreamReader(in);
         // stores the stream in large chunks for fast read and to prevent expensive I/O
         BufferedReader reader = new BufferedReader(isr)) {

      CsvParserSettings settings = new CsvParserSettings();
      settings.setHeaderExtractionEnabled(true); // expects header row like externalId,name,quantity,expiryDate
      // Configure other settings as needed (delimiter, quoting, etc.)

      CsvParser parser = new CsvParser(settings);
      parser.beginParsing(reader);

      try {
        UploadResult result;
        if (mode == Mode.ALL_OR_NOTHING) {
          result = processAllOrNothing(parser);
        } else {
          result = processChunked(parser, 1000);
        }
        // Status Update 2: Validation/Parsing complete (regardless of success/failure)
        updateStatus("VALIDATION_PARSING_COMPLETE", "Validation and parsing finished.");

        return result;
      } finally {
        // ensure parsing stops and resources are freed
        parser.stopParsing();
      }
    }
  }

  /**
   * ALL_OR_NOTHING: single DB transaction for the entire file.
   * Be careful: large files may cause memory pressure as we accumulate entities.
   */
  @Transactional
  protected UploadResult processAllOrNothing(CsvParser parser) {
    updateStatus("PROCESS All OR NOTHING STARTED", "Validation and parsing finished.");
//    batch holds the entire csv for validation
    List<Item> batch = new ArrayList<>();
    long processed = 0L;
    long failed = 0L;
    // Use ConcurrentHashMap for thread-safe counting, though it's single-threaded here.
    Map<String, Long> errorCounts = new ConcurrentHashMap<>();
    File errorReport = createTempFile("errors_all_or_nothing", ".csv");

    try (PrintWriter err = new PrintWriter(new FileWriter(errorReport))) {
      String[] row;
      // parseNext returns null when there are no more rows
      while ((row = parser.parseNext()) != null) {
        processed++;
        Optional<String> validation = validateRow(row);
        if (validation.isEmpty()) {
          Item item = rowToEntity(row);
          batch.add(item);
        } else {
          failed++;
          String errorMessage = validation.get();
          // Accumulate error count
          errorCounts.merge(errorMessage, 1L, Long::sum);
          // safe: errorMessage exists because isPresent() checked
          err.println(String.join(",", safeArray(row)) + "," + errorMessage);
        }
      }
      updateStatus("VALIDATION_PARSING_COMPLETE", "Validation and parsing finished.");

      // store all in one transaction
      itemRepository.saveAll(batch);
      itemRepository.flush();

      updateStatus("DB_COMMIT_SUCCESS", "Database transaction completed successfully."); // <-- Status Update 3 (Success)

      return new UploadResult(processed, batch.size(), failed, errorReport, new ValidationSummary(errorCounts));
    } catch (IOException ex) {
      updateStatus("FILE_WRITE_FAILED", "Failed to write error report.");
      throw new UncheckedIOException("Failed to write error report", ex);
    } catch (RuntimeException ex) {
      updateStatus("DB_COMMIT_FAILED", "Database transaction failed and rolled back."); // <-- Status Update 3 (Failure)
      throw ex;
    }
  }

  /**
   * CHUNK_COMMIT: commit per chunk to limit memory and transaction size.
   * Each chunk is saved in its own transaction via saveChunk(...) which is transactional.
   */
  protected UploadResult processChunked(CsvParser parser, int chunkSize) throws IOException {
    updateStatus("PROCESS CHUNK STARTED", "Validation and parsing finished.");
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
        Optional<String> validation = validateRow(row);
        if (validation.isEmpty()) {
          batch.add(rowToEntity(row));
        } else {
          failed++;
          String errorMessage = validation.get();
          // Accumulate error count
          errorCounts.merge(errorMessage, 1L, Long::sum);
          err.println(String.join(",", safeArray(row)) + "," + errorMessage);
        }

        if (batch.size() >= chunkSize) {
          inserted += saveChunk(batch);
          batch.clear();
        }
      }

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
    // Option: use jdbcTemplate.batchUpdate(...) or Postgres COPY for huge throughput.
    itemRepository.saveAll(chunk);
    itemRepository.flush();
    return chunk.size();
  }

  /**
   * Validate a CSV row.
   * Expecting columns [externalId, name, quantity, expiryDate]
   *
   * Returns Optional.empty() when valid, otherwise Optional.of(errorMessage).
   */
  private Optional<String> validateRow(String[] row) {
    if (row == null) return Optional.of("row is null");
    // Basic column count check
    if (row.length < 4) return Optional.of("too few columns");

    String externalId = (row[0] == null) ? "" : row[0].trim();
    String name = (row[1] == null) ? "" : row[1].trim();
    String qtyStr = (row[2] == null) ? "" : row[2].trim();
    String dateStr = (row[3] == null) ? "" : row[3].trim();

    if (externalId.isEmpty()) return Optional.of("externalId empty");
    if (name.isEmpty()) return Optional.of("name empty");

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

    // Optional DB uniqueness check (this does a DB hit per row; consider batching or prefetch)
    if (itemRepository.existsByExternalId(externalId)) {
      return Optional.of("duplicate externalId");
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
      File f = File.createTempFile(prefix, suffix);
      // option: f.deleteOnExit(); // be careful in long-running servers
      return f;
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
      out[i] = row[i] == null ? "" : row[i].replaceAll(",", ""); // remove commas to avoid CSV collisions
    }
    return out;
  }
}