package dao

import "context"

type MessagesRepository interface {
	Insert(ctx context.Context, message *Message) (*Message, error)
}

type messagesRepository struct{}

func NewMessagesRepository() MessagesRepository {
	return &messagesRepository{}
}

func (r *messagesRepository) Insert(ctx context.Context, message *Message) (*Message, error) {
	return insertRow(ctx, message)
}
