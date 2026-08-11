export function buildHitlResumeChatRequest(args: {
  conversationId: string;
  answer: string;
}): { message: string; conversationId: string } {
  return { message: args.answer, conversationId: args.conversationId };
}
