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
	db, err := GetDb(ctx)
	if err != nil {
		return nil, err
	}
	var result Message
	if _, err := db.NewInsert().Model(message).Returning("*").Exec(ctx, &result); err != nil {
		return nil, err
	}
	return &result, nil
}
