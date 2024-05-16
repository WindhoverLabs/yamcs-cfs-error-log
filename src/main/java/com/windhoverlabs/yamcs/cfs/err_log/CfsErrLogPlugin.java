/****************************************************************************
 *
 *   Copyright (c) 2022 Windhover Labs, L.L.C. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in
 *    the documentation and/or other materials provided with the
 *    distribution.
 * 3. Neither the name Windhover Labs nor the names of its
 *    contributors may be used to endorse or promote products derived
 *    from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS
 * FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS
 * OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED
 * AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN
 * ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 *
 *****************************************************************************/

package com.windhoverlabs.yamcs.cfs.err_log;

import com.google.common.io.BaseEncoding;
import com.windhoverlabs.yamcs.cfs.err_log.CfsErrLogPlugin.CFE_ES_ERLog_t;
import com.windhoverlabs.yamcs.cfs.err_log.api.CfsErrLogFileMode;
import java.io.BufferedWriter;
import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.yamcs.ConfigurationException;
import org.yamcs.Spec;
import org.yamcs.Spec.OptionType;
import org.yamcs.ValidationException;
import org.yamcs.YConfiguration;
import org.yamcs.YamcsServer;
import org.yamcs.events.EventProducer;
import org.yamcs.events.EventProducerFactory;
import org.yamcs.parameter.ParameterValue;
import org.yamcs.parameter.SystemParametersProducer;
import org.yamcs.parameter.SystemParametersService;
import org.yamcs.protobuf.Yamcs;
import org.yamcs.tctm.AbstractPacketPreprocessor;
import org.yamcs.tctm.AbstractTmDataLink;
import org.yamcs.tctm.Link.Status;
import org.yamcs.utils.FileUtils;
import org.yamcs.xtce.Parameter;
import org.yamcs.yarch.FileSystemBucket;
import org.yamcs.yarch.YarchDatabase;
import org.yamcs.yarch.YarchDatabaseInstance;
import org.yamcs.yarch.rocksdb.protobuf.Tablespace.ObjectProperties;

public class CfsErrLogPlugin extends AbstractTmDataLink
    implements Runnable, SystemParametersProducer {

  /*
   ** Debug variables type
   */
  class CFE_ES_DebugVariables_t {
    long DebugFlag;
    long WatchdogWriteFlag;
    long PrintfEnabledFlag;
    long LastAppId;
  }

  class CFE_TIME_SysTime_t {
    long Seconds;
    /** < \brief Number of seconds since epoch */
    long Subseconds;
    /** < \brief Number of subseconds since epoch (LSB = 2^(-32) seconds) */
  }

  /** Java-like structure equivalent of CFE_ES_ERLog_t */
  class CFE_ES_ERLog_t {
    long LogEntryType; /* What type of log entry */
    long ResetType; /* Main cause for the reset */
    long ResetSubtype; /* The sub-type for the reset */
    long BootSource; /* The boot source  */
    long ProcessorResetCount; /* The number of processor resets */
    long MaxProcessorResetCount; /* The maximum number before a Power On */
    CFE_ES_DebugVariables_t DebugVars = new CFE_ES_DebugVariables_t(); /* ES Debug variables */
    CFE_TIME_SysTime_t TimeCode = new CFE_TIME_SysTime_t(); /* Time code */
    char[] Description = new char[80]; /* The ascii data for the event */
    long ContextSize; /* Indicates the context data is valid */
    long AppID; /* The application ID */
    ArrayList<Long> Context = new ArrayList<Long>(); /* cpu context */
  }
  /* Configuration Defaults */
  static long POLLING_PERIOD_DEFAULT = 1000;
  static int INITIAL_DELAY_DEFAULT = -1;
  static boolean IGNORE_INITIAL_DEFAULT = true;
  static boolean CLEAR_BUCKETS_AT_STARTUP_DEFAULT = false;
  static boolean DELETE_FILE_AFTER_PROCESSING_DEFAULT = false;

  private Parameter writeToFileSuccessCountParam;
  private int writeToFileSuccessCount;

  /* Configuration Parameters */
  protected long initialDelay;
  protected long period;
  protected boolean ignoreInitial;
  protected boolean clearBucketsAtStartup;
  protected boolean deleteFileAfterProcessing;
  protected int CFE_FS_ES_ERRLOG_SUBTYPE;
  private int CFE_FS_ES_ERLOG_SIZE;
  private int CFE_FS_ES_ERLOG_CONTEXT_ARRAY_SIZE;
  private int CFE_FS_ES_ER_ENTRY_SIZE;
  private boolean readUntilEOF;
  protected int DS_TOTAL_FNAME_BUFSIZE;
  private int ErrorEntrySize;
  private String outputFile;

  /* Internal member attributes. */
  protected List<FileSystemBucket> buckets;
  protected FileSystemBucket sysLogBucket;
  protected WatchService watcher;
  protected List<WatchKey> watchKeys;
  protected Thread thread;

  private String eventStreamName;

  static final String RECTIME_CNAME = "rectime";
  static final String DATA_EVENT_CNAME = "data";

  private EventProducer eventProducer;

  /* Constants */
  static final byte[] CFE_FS_FILE_CONTENT_ID_BYTE =
      BaseEncoding.base16().lowerCase().decode("63464531".toLowerCase());

  private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

  private ByteOrder byteOrder;
  private CfsErrLogFileMode mode;

  public CfsErrLogFileMode getMode() {
    return mode;
  }

  public void setMode(CfsErrLogFileMode mode) {
    this.mode = mode;
  }

  @Override
  public Spec getSpec() {
    Spec spec = new Spec();
    Spec sysLogFileConfigSpec = new Spec();

    /* Define our configuration parameters. */
    spec.addOption("name", OptionType.STRING).withRequired(true);
    spec.addOption("class", OptionType.STRING).withRequired(true);
    spec.addOption("CFE_FS_ES_ERLOG_SUBTYPE", OptionType.INTEGER).withRequired(true);
    spec.addOption("CFE_FS_ES_ERLOG_SIZE", OptionType.INTEGER).withRequired(true);
    spec.addOption("CFE_FS_ES_ERLOG_CONTEXT_ARRAY_SIZE", OptionType.INTEGER).withRequired(true);
    spec.addOption("CFE_FS_ES_ER_ENTRY_SIZE", OptionType.INTEGER).withRequired(true);
    spec.addOption("readUntilEOF", OptionType.BOOLEAN).withRequired(true);
    spec.addOption("stream", OptionType.STRING).withRequired(true);
    spec.addOption("initialDelay", OptionType.INTEGER)
        .withDefault(INITIAL_DELAY_DEFAULT)
        .withRequired(false);
    spec.addOption("pollingPeriod", OptionType.INTEGER)
        .withDefault(POLLING_PERIOD_DEFAULT)
        .withRequired(false);
    spec.addOption("ignoreInitial", OptionType.BOOLEAN)
        .withDefault(IGNORE_INITIAL_DEFAULT)
        .withRequired(false);
    spec.addOption("deleteFileAfterProcessing", OptionType.BOOLEAN)
        .withDefault(DELETE_FILE_AFTER_PROCESSING_DEFAULT)
        .withRequired(false);
    spec.addOption("clearBucketsAtStartup", OptionType.BOOLEAN)
        .withDefault(CLEAR_BUCKETS_AT_STARTUP_DEFAULT)
        .withRequired(false);
    spec.addOption("buckets", OptionType.LIST_OR_ELEMENT)
        .withElementType(OptionType.STRING)
        .withRequired(true);
    sysLogFileConfigSpec.addOption("mode", OptionType.STRING).withRequired(true);
    sysLogFileConfigSpec.addOption("sysLogBucket", OptionType.STRING).withRequired(true);
    sysLogFileConfigSpec.addOption("outputFile", OptionType.STRING).withRequired(true);
    spec.addOption("sysLogFileConfig", OptionType.MAP)
        .withRequired(true)
        .withSpec(sysLogFileConfigSpec);

    return spec;
  }

  @Override
  public void init(String yamcsInstance, String serviceName, YConfiguration config) {
    super.init(yamcsInstance, serviceName, config);

    /* Local variables */
    List<String> bucketNames;
    String sysLogBucketName;
    this.config = config;
    /* Validate the configuration that the user passed us. */
    try {
      config = getSpec().validate(config);
    } catch (ValidationException e) {
      log.error("Failed configuration validation.", e);
    }

    /* Instantiate our member objects. */
    this.buckets = new LinkedList<FileSystemBucket>();
    this.watchKeys = new LinkedList<WatchKey>();
    eventProducer =
        EventProducerFactory.getEventProducer(
            yamcsInstance, this.getClass().getCanonicalName(), 10000);

    System.out.println("eventProducer:" + eventProducer);

    YConfiguration csvConfig = config.getConfig("sysLogFileConfig");

    mode = getMode(csvConfig);
    outputFile = csvConfig.getString("outputFile");
    sysLogBucketName = csvConfig.getString("sysLogBucket");

    byteOrder = AbstractPacketPreprocessor.getByteOrder(csvConfig);

    /* Read in our configuration parameters. */
    bucketNames = config.getList("buckets");
    this.CFE_FS_ES_ERRLOG_SUBTYPE = config.getInt("CFE_FS_ES_ERLOG_SUBTYPE");
    this.CFE_FS_ES_ERLOG_SIZE = config.getInt("CFE_FS_ES_ERLOG_SIZE");
    this.CFE_FS_ES_ER_ENTRY_SIZE = config.getInt("CFE_FS_ES_ER_ENTRY_SIZE");
    this.CFE_FS_ES_ERLOG_CONTEXT_ARRAY_SIZE = config.getInt("CFE_FS_ES_ERLOG_CONTEXT_ARRAY_SIZE");
    this.initialDelay = config.getLong("initialDelay", INITIAL_DELAY_DEFAULT);
    this.period = config.getLong("pollingPeriod");
    this.ignoreInitial = config.getBoolean("ignoreInitial");
    this.readUntilEOF = config.getBoolean("readUntilEOF");
    this.clearBucketsAtStartup =
        config.getBoolean("clearBucketsAtStartup", CLEAR_BUCKETS_AT_STARTUP_DEFAULT);
    this.deleteFileAfterProcessing = config.getBoolean("deleteFileAfterProcessing");

    /* Create the WatchService from the file system.  We're going to use this later to monitor
     * the files and directories in YAMCS Buckets. */
    try {
      watcher = FileSystems.getDefault().newWatchService();
    } catch (IOException e1) {
      e1.printStackTrace();
    }

    /* Iterate through the bucket names passed to us by the configuration file.  We're going
    to add the buckets
        * to our internal list so we can process them later. */
    for (String bucketName : bucketNames) {
      YarchDatabaseInstance yarch = YarchDatabase.getInstance(YamcsServer.GLOBAL_INSTANCE);

      try {
        FileSystemBucket bucket;
        bucket = (FileSystemBucket) yarch.getBucket(bucketName);
        buckets.add(bucket);
      } catch (IOException e) {
        e.printStackTrace();
      }
    }

    /* Iterate through the bucket and create a WatchKey on the path.  This will be used in the
    main
        * thread to get notification of any new or modified files. */
    for (FileSystemBucket bucket : buckets) {
      Path fullPath = Paths.get(bucket.getBucketRoot().toString()).toAbsolutePath();
      try {
        WatchKey key =
            fullPath.register(
                watcher,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY);

        this.watchKeys.add(key);
      } catch (IOException e1) {
        e1.printStackTrace();
        break;
      }
    }

    YarchDatabaseInstance yarch = YarchDatabase.getInstance(YamcsServer.GLOBAL_INSTANCE);
    try {
      sysLogBucket = (FileSystemBucket) yarch.getBucket(sysLogBucketName);
    } catch (IOException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
  }

  @Override
  public void doDisable() {
    /* If the thread is created, interrupt it. */
    if (thread != null) {
      thread.interrupt();
    }
  }

  @Override
  public void doEnable() {
    /* Create and start the new thread. */
    thread = new Thread(this);
    thread.setName(this.getClass().getSimpleName() + "-" + linkName);
    thread.start();
  }

  @Override
  public String getDetailedStatus() {
    if (isDisabled()) {
      return String.format("DISABLED");
    } else {
      return String.format("OK, received %d cfs sys logs", packetCount.get());
    }
  }

  @Override
  protected Status connectionStatus() {
    return Status.OK;
  }

  @Override
  protected void doStart() {
    if (!isDisabled()) {
      doEnable();
    }
    notifyStarted();
  }

  @Override
  protected void doStop() {
    if (thread != null) {
      thread.interrupt();
    }

    notifyStopped();
  }

  @Override
  public void run() {
    /* Delay the start, if configured to do so. */
    if (initialDelay > 0) {
      try {
        Thread.sleep(initialDelay);
        initialDelay = -1;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }

    /* Are we supposed to ignore the initial files in the buckets? */
    if (!ignoreInitial) {
      /* No.  Process them all.  Lets start by iterating through the buckets. */
      for (FileSystemBucket bucket : buckets) {
        try {
          /* Get the contents of the bucket. */
          List<ObjectProperties> fileOjects = bucket.listObjects();

          /* Iterate through the objects, which should be files and directories. */
          for (ObjectProperties fileObject : fileOjects) {
            /* Get the full absolute path to the file/directory. */
            Path fullPath =
                Paths.get(bucket.getBucketRoot().toString(), fileObject.getName()).toAbsolutePath();

            /* Is this a file? */
            if (Files.isRegularFile(fullPath)) {
              /* It is.  Parse the file. */
              ParseFile(fullPath);
            }
          }
        } catch (IOException e) {
          e.printStackTrace();
        }
      }
    }

    /* Are we supposed to clear all the buckets at startup? */
    if (clearBucketsAtStartup) {
      /* Yes we are.  Iterate through all the buckets. */
      for (FileSystemBucket bucket : buckets) {
        try {
          /* Recursively delete the contents of the bucket, which is a directory. */
          log.info("Clearing '" + bucket.getBucketRoot() + "'");
          FileUtils.deleteContents(bucket.getBucketRoot());
        } catch (IOException e1) {
          e1.printStackTrace();
        }
      }
    }

    /* Enter our main loop */
    while (isRunningAndEnabled()) {
      /* Iterate through all our watch keys. */
      for (WatchKey watchKey : this.watchKeys) {
        Path dir = (Path) watchKey.watchable();

        /* Iterate through the events queued in this watch key, if any. */
        for (WatchEvent<?> evnt : watchKey.pollEvents()) {
          WatchEvent.Kind<?> kind = evnt.kind();

          /* This key is registered only for ENTRY_CREATE events,
          but an OVERFLOW event can occur regardless if events
          are lost or discarded. */
          if (kind == StandardWatchEventKinds.OVERFLOW) {
            log.error("WatchEvent OVERFLOW detected.");
            watchKey.reset();
            continue;
          }

          if (kind == StandardWatchEventKinds.ENTRY_MODIFY) {
            /* The filename is the context of the event. */
            WatchEvent<Path> ev = (WatchEvent<Path>) evnt;
            Path fullPath = dir.resolve(ev.context());

            /* Check if the file exists first.  These events sometimes pop up when a file is deleted, so we don't
             * want to do anything if the file was actually deleted. */
            if (java.nio.file.Files.exists(fullPath)) {
              /* It exists.  Is this a file or directory? */
              if (Files.isRegularFile(fullPath)) {
                /* It is a file.  Parse it. */
                ParseFile(fullPath);
              }
            }

            /* Reset the key -- this step is critical if you want to
            receive further watch events.  If the key is no longer valid,
            the directory is inaccessible so exit the loop. */
            watchKey.reset();
          }
        }
      }

      /* Sleep for the configured amount of time.  We normally sleep so we don't needlessly chew up resources. */
      try {
        Thread.sleep(this.period);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private void ParseFile(Path inputFile) {
    log.debug("Interrogating file " + inputFile);

    try {
      /* Create a DataInputStream from this FileInputStream. */
      InputStream inputStream = new FileInputStream(inputFile.toString());
      DataInputStream dataInputStream = new DataInputStream(inputStream);

      /* Check for the "cFE1" tattoo */
      byte[] nextWord = new byte[4];
      dataInputStream.read(nextWord, 0, 4);
      if (java.util.Arrays.equals(nextWord, CFE_FS_FILE_CONTENT_ID_BYTE)) {
        /* It does have the "cFE1" tattoo.  Now read the CFE FS header. */
        int subType;
        int length;
        int spacecraftID;
        int processorID;
        int applicationID;
        int timeSeconds;
        int timeSubSeconds;
        byte[] descriptionBytes = new byte[32];
        String description;

        log.debug("CFE log file detected");

        /* Read each field 1 byte at a time so we can ensure we process the fields with the correct
         * endianness and ABI.
         */
        dataInputStream.read(nextWord, 0, 4);
        subType = (nextWord[0] << 24) + (nextWord[1] << 16) + (nextWord[2] << 8) + (nextWord[3]);

        dataInputStream.read(nextWord, 0, 4);
        length = (nextWord[0] << 24) + (nextWord[1] << 16) + (nextWord[2] << 8) + (nextWord[3]);

        dataInputStream.read(nextWord, 0, 4);
        spacecraftID =
            (nextWord[0] << 24) + (nextWord[1] << 16) + (nextWord[2] << 8) + (nextWord[3]);

        dataInputStream.read(nextWord, 0, 4);
        processorID =
            (nextWord[0] << 24) + (nextWord[1] << 16) + (nextWord[2] << 8) + (nextWord[3]);

        dataInputStream.read(nextWord, 0, 4);
        applicationID =
            (nextWord[0] << 24) + (nextWord[1] << 16) + (nextWord[2] << 8) + (nextWord[3]);

        dataInputStream.read(nextWord, 0, 4);
        timeSeconds =
            (nextWord[0] << 24) + (nextWord[1] << 16) + (nextWord[2] << 8) + (nextWord[3]);

        dataInputStream.read(nextWord, 0, 4);
        timeSubSeconds =
            (nextWord[0] << 24) + (nextWord[1] << 16) + (nextWord[2] << 8) + (nextWord[3]);

        dataInputStream.read(descriptionBytes, 0, 32);
        description = new String(descriptionBytes, StandardCharsets.UTF_8);

        /* Is this a SYSLOG log? */
        if (subType == CFE_FS_ES_ERRLOG_SUBTYPE) {
          /* It is a EVS log.  Start reading the secondary header for the EVS log. */

          log.info(
              "Parsing EVS log "
                  + inputFile.toString()
                  + "."
                  + "  SubType="
                  + subType
                  + "  Length="
                  + length
                  + "  SCID="
                  + spacecraftID
                  + "  ProcID="
                  + processorID
                  + "  AppID="
                  + applicationID);

          StringBuilder logContentBuilder = new StringBuilder();

          ArrayList<Byte> errorLogData = new ArrayList<Byte>();
          eventProducer.sendInfo("Reading new error log entries");

          ArrayList<CFE_ES_ERLog_t> errorLogRecords = new ArrayList<CFE_ES_ERLog_t>();

          if (this.readUntilEOF) {
            //          Read all bytes until we reach EOF
            while (true) {

              byte[] errorLogEntryData = new byte[this.CFE_FS_ES_ER_ENTRY_SIZE];
              try {
                int bytesRead = inputStream.read(errorLogEntryData);
                if (bytesRead == -1) {
                  eventProducer.sendInfo("Reached EOF of error log file");
                  break;
                } else {
                  if (bytesRead < this.CFE_FS_ES_ER_ENTRY_SIZE) {
                    eventProducer.sendCritical("Incomplete CFE_ES_ERLog_t. Skipping ");
                    //                    TODO:Dump Incomplete entry to some configurable stream
                  } else {
                    CFE_ES_ERLog_t errorLogRecord = readErrorLogEntry(errorLogEntryData);
                    errorLogRecords.add(errorLogRecord);
                  }
                }
              } catch (IOException e) {
                // TODO Auto-generated catch block
                //      e.printStackTrace();
                eventProducer.sendCritical(
                    String.format("There was an error parsing the logEntry: %s", e.getMessage()));
                break;
              }
            }
          } else {

            //            int bytesRead = 0;
            //
            //            Optional<CFE_ES_ERLog_t> optionalLog = readErrorLogEntry(dataInputStream);
            //
            //            //          Read all bytes until we reach the number of bytes specified by
            //            // this.CFE_FS_ES_ERLOG_SIZE
            //            while (optionalLog.isPresent() && bytesRead < this.CFE_FS_ES_ERLOG_SIZE) {
            //              optionalLog = readErrorLogEntry(dataInputStream);
            //
            //              if (optionalLog.isPresent()) {
            //                errorLogRecords.add(optionalLog.get());
            //              } else {
            //                break;
            //              }
            //
            //              bytesRead += this.CFE_FS_ES_ER_ENTRY_SIZE;
            //            }
            //
            //            if (dataInputStream.read() != -1) {
            //
            //              eventProducer.sendWarning(
            //                  String.format(
            //                      "Read all bytes specified in CFE_FS_ES_ERLOG_SIZE(%d), but EOF
            // has not been reached yet. ",
            //                      this.CFE_FS_ES_ERLOG_SIZE));
            //            } else {
            //
            //            }
          }

          eventProducer.sendInfo(
              String.format("Read %d error log entries", errorLogRecords.size()));

          //          logContent = logContentBuilder.toString();
          //          updateStats(logContent.length());
          BufferedWriter writer = null;
          //
          switch (mode) {
            case APPEND:
              if (sysLogBucket != null && outputFile != null) {
                writer =
                    Files.newBufferedWriter(
                        Paths.get(
                            sysLogBucket.getBucketRoot().toAbsolutePath().toString(), outputFile),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);

              } else {
                writer = null;
              }

              eventProducer.sendInfo(
                  "Invoked during APPEND mode. Data will be appended at the end of the file");
              writeToCSV(writer, errorLogRecords);
              break;
            case INACTIVE:
              eventProducer.sendInfo(
                  "Invoked during INACTIVE mode. No data will be written to file.");
              break;
            case REPLACE:
              if (sysLogBucket != null && outputFile != null) {
                writer =
                    Files.newBufferedWriter(
                        Paths.get(
                            sysLogBucket.getBucketRoot().toAbsolutePath().toString(), outputFile));
                eventProducer.sendInfo(
                    "Invoked during REPLACE mode. The contents of the file will be verwritten.");
                writeToCSV(writer, errorLogRecords);
              } else {
                if (eventProducer != null) {
                  eventProducer.sendWarning(
                      String.format(
                          "Not able to write to file. Does the bucket \"%s\" exist?",
                          sysLogBucket.getName()));
                }
                writer = null;
              }

              break;
            default:
              break;
          }

          /* Are we supposed to delete the file? */
          if (this.deleteFileAfterProcessing) {
            /* Yes.  Delete it with extreme prejudice. */
            log.info("Deleting '" + inputFile + "'");
            java.nio.file.Files.delete(inputFile);
          }
        }
      }
    } catch (FileNotFoundException e) {
      e.printStackTrace();
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  @Override
  public void setupSystemParameters(SystemParametersService sysParamCollector) {
    super.setupSystemParameters(sysParamCollector);
    writeToFileSuccessCountParam =
        sysParamCollector.createSystemParameter(
            linkName + "/writeToFileSuccessCountParam",
            Yamcs.Value.Type.UINT64,
            "Every time we successfully write to the file, this count increases.");
  }

  @Override
  public List<ParameterValue> getSystemParameters() {
    long time = getCurrentTime();

    ArrayList<ParameterValue> list = new ArrayList<>();
    try {
      collectSystemParameters(time, list);
    } catch (Exception e) {
      log.error("Exception caught when collecting link system parameters", e);
    }
    return list;
  }

  @Override
  protected void collectSystemParameters(long time, List<ParameterValue> list) {
    super.collectSystemParameters(time, list);
    list.add(
        SystemParametersService.getPV(writeToFileSuccessCountParam, time, writeToFileSuccessCount));
  }

  private static CfsErrLogFileMode getMode(YConfiguration config) {
    String mode = config.getString("mode");
    if ("APPEND".equalsIgnoreCase(mode)) {
      return CfsErrLogFileMode.APPEND;
    } else if ("REPLACE".equalsIgnoreCase(mode)) {
      return CfsErrLogFileMode.REPLACE;
    } else if ("INACTIVE".equalsIgnoreCase(mode)) {
      return CfsErrLogFileMode.INACTIVE;
    } else {
      throw new ConfigurationException(
          "Invalid '" + mode + "' mode specified. Use one of APPEND, REPLACE or INACTIVE.");
    }
  }

  private void writeToCSV(BufferedWriter writer, ArrayList<CFE_ES_ERLog_t> errorLogRecords) {
    CSVPrinter csvPrinter = null;
    ArrayList<String> columnHeaders = new ArrayList<String>();
    try {
      columnHeaders.add("LogEntryType");
      columnHeaders.add("ResetType");
      columnHeaders.add("ResetSubtype");
      columnHeaders.add("BootSource");
      columnHeaders.add("ProcessorResetCount");
      columnHeaders.add("MaxProcessorResetCount");

      //	      CFE_ES_DebugVariables_t
      columnHeaders.add("DebugFlag");
      columnHeaders.add("WatchdogWriteFlag");
      columnHeaders.add("PrintfEnabledFlag");
      columnHeaders.add("LastAppId");

      //	      CFE_TIME_SysTime_t
      columnHeaders.add("Seconds");
      columnHeaders.add("Subseconds");

      columnHeaders.add("Description");
      columnHeaders.add("ContextSize");
      columnHeaders.add("AppID");
      columnHeaders.add("Context");

      csvPrinter = new CSVPrinter(writer, CSVFormat.DEFAULT);

    } catch (IOException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
      return;
    }
    try {
      csvPrinter.printRecord(columnHeaders);

      for (CFE_ES_ERLog_t e : errorLogRecords) {
        ArrayList<String> errRecord = new ArrayList<String>();
        errRecord.add(Long.toString(e.LogEntryType));
        errRecord.add(Long.toString(e.ResetType));

        errRecord.add(Long.toString(e.ResetSubtype));
        errRecord.add(Long.toString(e.BootSource));
        errRecord.add(Long.toString(e.ProcessorResetCount));
        errRecord.add(Long.toString(e.MaxProcessorResetCount));
        errRecord.add(Long.toString(e.DebugVars.DebugFlag));
        errRecord.add(Long.toString(e.DebugVars.WatchdogWriteFlag));
        errRecord.add(Long.toString(e.DebugVars.PrintfEnabledFlag));
        errRecord.add(Long.toString(e.DebugVars.LastAppId));
        errRecord.add(Long.toString(e.TimeCode.Seconds));
        errRecord.add(Long.toString(e.TimeCode.Subseconds));

        //        NOTE:If I use the regular String constructor, then it messes up the csv output.
        StringBuilder desc = new StringBuilder();
        for (char character : e.Description) {
          if (character != 0x00) {
            desc.append(character);
          } else {
            break;
          }
        }
        errRecord.add(desc.toString());

        errRecord.add(Long.toString(e.ContextSize));

        errRecord.add(Long.toString(e.AppID));

        errRecord.add(e.Context.toString());
        csvPrinter.printRecord(errRecord);
      }

    } catch (IOException e) {
      e.printStackTrace();
    }

    try {
      csvPrinter.flush();
      csvPrinter.close();
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private CFE_ES_ERLog_t readErrorLogEntry(byte[] errorLogEntryData) {
    CFE_ES_ERLog_t errorLogEntry = new CFE_ES_ERLog_t();

    ByteBuffer data = ByteBuffer.wrap(errorLogEntryData);
    data.order(ByteOrder.LITTLE_ENDIAN);
    errorLogEntry.LogEntryType = data.getInt();
    errorLogEntry.ResetType = data.getInt();
    errorLogEntry.ResetSubtype = data.getInt();
    errorLogEntry.BootSource = data.getInt();
    errorLogEntry.ProcessorResetCount = data.getInt();
    errorLogEntry.MaxProcessorResetCount = data.getInt();

    errorLogEntry.DebugVars.DebugFlag = data.getInt();
    errorLogEntry.DebugVars.WatchdogWriteFlag = data.getInt();
    errorLogEntry.DebugVars.PrintfEnabledFlag = data.getInt();
    errorLogEntry.DebugVars.LastAppId = data.getInt();

    errorLogEntry.TimeCode.Seconds = data.getInt();
    errorLogEntry.TimeCode.Subseconds = data.getInt();

    byte[] descriptionData = new byte[80];

    data.get(descriptionData);
    for (int i = 0; i < descriptionData.length; i++) {
      errorLogEntry.Description[i] = (char) descriptionData[i];
    }

    errorLogEntry.ContextSize = data.getInt();
    errorLogEntry.AppID = data.getInt();
    for (int i = 0; i < this.CFE_FS_ES_ERLOG_CONTEXT_ARRAY_SIZE; i++) {
      errorLogEntry.Context.add((long) data.getInt());
    }
    return errorLogEntry;
  }
}
