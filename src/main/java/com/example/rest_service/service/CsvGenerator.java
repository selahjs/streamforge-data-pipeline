package com.example.rest_service.service;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

public class CsvGenerator {

  // Target file size (approximately 1 GB)
  private static final long TARGET_FILE_SIZE_BYTES = 1024L * 1024L * 1024L; // 1 GB

  // File path where the CSV will be saved
  private static final String FILE_PATH = "large_test_data.csv";

  // Fixed length of a typical data row (used for size estimation)
  // externalId (10 digits) + quantity (4 digits) + name (15 chars) + date (10 chars) + 3 commas + 1 newline
  private static final int ESTIMATED_ROW_LENGTH = 10 + 4 + 15 + 10 + 4; // Approx 43 characters/bytes per row

  public static void main(String[] args) {
    long linesToWrite = TARGET_FILE_SIZE_BYTES / ESTIMATED_ROW_LENGTH;
    long bytesWritten = 0;
    long startTime = System.currentTimeMillis();
    long uniqueIdCounter = 1000000000L; // Starting with a 10-digit unique number

    System.out.println("Starting CSV generation...");
    System.out.printf("Target Size: 1 GB (%d bytes)\n", TARGET_FILE_SIZE_BYTES);
    System.out.printf("Estimated Rows: %,d\n", linesToWrite);

    try (BufferedWriter writer = new BufferedWriter(new FileWriter(FILE_PATH))) {

      // 1. Write Header
      String header = "externalId,name,quantity,expiryDate\n";
      writer.write(header);
      bytesWritten += header.getBytes().length;

      // 2. Generate Data Rows
      DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

      for (long i = 0; i < linesToWrite; i++) {

        // externalId: Unique number
        String externalId = String.valueOf(uniqueIdCounter++);

        // name: Simple, random name for variety
        String name = "Item_" + ThreadLocalRandom.current().nextInt(1, 1000);

        // quantity: Random number between 1 and 9999
        String quantity = String.valueOf(ThreadLocalRandom.current().nextInt(1, 10000));

        // expiryDate: Random date within the next year
        LocalDate expiryDate = LocalDate.now().plusDays(ThreadLocalRandom.current().nextLong(1, 365));
        String dateStr = expiryDate.format(DATE_FMT);

        // Construct the row
        String row = String.join(",", externalId, name, quantity, dateStr) + "\n";

        writer.write(row);
        bytesWritten += row.getBytes().length;

        // Simple progress indicator
        if (i % (linesToWrite / 10) == 0 && i != 0) {
          System.out.printf("Progress: %d%%. Lines written: %,d\n", (i * 100 / linesToWrite), i);
        }
      }

      System.out.println("Writing complete. Flushing buffer...");
      writer.flush(); // Ensure all data is written to the file

    } catch (IOException e) {
      System.err.println("An error occurred during file writing: " + e.getMessage());
    }

    long endTime = System.currentTimeMillis();
    long durationSeconds = (endTime - startTime) / 1000;

    System.out.println("--- Generation Summary ---");
    System.out.printf("File Path: %s\n", FILE_PATH);
    System.out.printf("Actual Bytes Written (approx): %,d\n", bytesWritten);
    System.out.printf("Total Execution Time: %d seconds\n", durationSeconds);
    System.out.println("File is ready for upload performance testing.");
  }
}
