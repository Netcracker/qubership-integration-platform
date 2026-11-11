exchange.setProperty('snapshotMcpDuplicateHandlerReached', true)
exchange.getMessage().setBody('Duplicate handled')
