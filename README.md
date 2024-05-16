[![CI](https://github.com/WindhoverLabs/yamcs-cfs-ds/actions/workflows/ci.yml/badge.svg)](https://github.com/WindhoverLabs/yamcs-cfs-ds/actions/workflows/ci.yml)
<!-- [![Coverage Status](https://coveralls.io/repos/github/WindhoverLabs/yamcs-cfs-ds/badge.svg?branch=main)](https://coveralls.io/github/WindhoverLabs/yamcs-cfs-ds?branch=main) -->
# yamcs-cfs-error-log
A YAMCS plugin for the Core Flight System (CFS) Error Log format. It has the ability to parse
error logs by removing the `CFE_FS_Header_t`  and dumping the contents to a csv file. 

# Table of Contents
1. [Dependencies](#dependencies)
2. [To Build](#to_build)

### Dependencies <a name="dependencies"></a>
- `Java 11`
- `Maven`
- `YAMCS>=5.6.2`
- `Ubuntu 16/18/20`

### To Build <a name="to_build"></a>

*NOTE*:Makes an assumption that error log entries look like this structure:

```C
typedef struct
{
    uint32                  LogEntryType;                                             
    uint32                  ResetType;                                                
    uint32                  ResetSubtype;                                             
    uint32                  BootSource;                                               
    uint32                  ProcessorResetCount;                                      
    uint32                  MaxProcessorResetCount;                                   
    CFE_ES_DebugVariables_t DebugVars;                                                
    CFE_TIME_SysTime_t      TimeCode;                                                 
    char                    Description[80];                                          
    uint32                  ContextSize;                                              
    uint32                  AppID;                                                    
    uint32                  Context[CFE_ES_ER_LOG_MAX_CONTEXT_SIZE / sizeof(uint32)]; 
} CFE_ES_ERLog_t;
```

```BASH
mvn install -DskipTests
```

To package it:
```BASH
mvn package -DskipTests
mvn dependency:copy-dependencies
```

The `package` command will output a jar file at `yamcs-cfs-error-log/target`.
Note the `dependency:copy-dependencies` command; this will copy all of the jars to the `yamcs-cfs-error-log/target/dependency` directory. Very useful for integrating third-party dependencies.

### To Integrate with YAMCS
This plugin functions as a YAMCS Telemetry Provider and will appear as a Datalink.  To integrate this plugin, add the
"com.windhoverlabs.yamcs.cfs.err_log.CfsErrLogPlugin" plugin to the "dataLinks" section of the YAMCS instance configuration. 
For example:
```yaml
dataLinks:
  - name: err-logs
    class: com.windhoverlabs.yamcs.cfs.err_log.CfsErrLogPlugin
    stream: tm_realtime
    buckets: ["cfdpDown"]
    CFE_FS_ES_ERLOG_SUBTYPE: 1
    CFE_FS_ES_ER_ENTRY_SIZE: 264  # sizeof(CFE_ES_ERLog_t) 
    CFE_FS_ES_ERLOG_CONTEXT_ARRAY_SIZE: 32
    # CFE_FS_ES_ERLOG_SIZE = CFE_ES_ER_LOG_ENTRIES * sizeof(CFE_ES_ERLog_t) 
    CFE_FS_ES_ERLOG_SIZE: 5280  # Ignored when readUntilEOF is true
    readUntilEOF: false
    errLogFileConfig:
      mode: APPEND # APPEND, REPLACE, INACTIVE
      outputFile: cfe_es_err_log.csv
      errLogBucket: "cfdpDown"
```


### CSV Mode API

```python
import requests
r = requests.post('http://127.0.0.1:8090/api/fsw/err_log/csv/mode/',
                  json={"instance": "fsw",
                        "linkName": "err-logs",
                        "mode": "INACTIVE"})
```
```python
import requests
r = requests.post('http://127.0.0.1:8090/api/fsw/err_log/csv/mode/',
                  json={"instance": "fsw",
                        "linkName": "err-logs",
                        "mode": "APPEND"})
```
```python
import requests
r = requests.post('http://127.0.0.1:8090/api/fsw/err_log/csv/mode/',
                  json={"instance": "fsw",
                        "linkName": "err-logs",
                        "mode": "REPLACE"})
```
