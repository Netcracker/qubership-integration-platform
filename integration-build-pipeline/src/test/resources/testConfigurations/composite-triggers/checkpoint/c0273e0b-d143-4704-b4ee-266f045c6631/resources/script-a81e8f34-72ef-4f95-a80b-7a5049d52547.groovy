def isCheckpoint = exchange.getProperty('originalSessionId', String.class) != null;
if (!isCheckpoint){
  throw new RuntimeException('Checkpoint invocation failed before retry.');
}
