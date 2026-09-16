def isCheckpoint = exchange.getProperty('originalSessionId', String) != null
if (!isCheckpoint){
  throw new RuntimeException('Checkpoint invocation failed before retry.')
}
