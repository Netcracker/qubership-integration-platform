package controllers

import (
	"github.com/gofiber/fiber/v2"
)

// ServiceMode tells the front end whether it talks to a production installation.
type ServiceMode struct {
	Production bool `json:"production"`
} // @name ServiceMode

type serviceModeController struct {
	production bool
}

func newServiceModeController(production bool) *serviceModeController {
	return &serviceModeController{production: production}
}

// GetMode
// @Summary Get service mode
// @ID getModeV1
// @Tags V1, Service
// @Produce json
// @Success    200    {object}       ServiceMode
// @Router /api/v1/mode [get]
func (c *serviceModeController) GetMode(ctx *fiber.Ctx) error {
	return ctx.Status(fiber.StatusOK).JSON(ServiceMode{Production: c.production})
}
