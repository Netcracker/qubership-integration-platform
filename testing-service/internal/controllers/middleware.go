package controllers

import (
	"github.com/gofiber/fiber/v2"

	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/config"
	"github.com/Netcracker/qubership-integration-platform/testing-service/internal/dao"
)

// CurrentUserMiddleware puts the caller into the request context, where the audit
// hook picks it up. bun constructs the model the hook hangs off, so the context
// is the only seam through which the user reaches an audited write.
func CurrentUserMiddleware(currentUser config.CurrentUserFunc) fiber.Handler {
	return func(ctx *fiber.Ctx) error {
		user := dao.DefaultUser
		if currentUser != nil {
			if resolved := currentUser(ctx.UserContext()); resolved != "" {
				user = resolved
			}
		}
		ctx.SetUserContext(dao.WithCurrentUser(ctx.UserContext(), user))
		return ctx.Next()
	}
}
