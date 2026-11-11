def inputBytes = exchange.getMessage().getBody(byte[])
exchange.setProperty('snapshotSftpScriptLength', inputBytes.length)
if (exchange.getProperty('snapshotFail', Boolean)) {
    throw new IllegalStateException('SFTP snapshot failure')
}
exchange.getMessage().setBody('Triggered')
