/**
 * @jest-environment jsdom
 */
import { afterEach, describe, expect, it, jest } from "@jest/globals";
import { act, render } from "@testing-library/react";
import { AutoHeight } from "../../src/components/AutoHeight";

function mockRect(overrides: Partial<DOMRect> = {}): DOMRect {
  return {
    top: 0,
    bottom: 0,
    left: 0,
    right: 0,
    width: 0,
    height: 0,
    x: 0,
    y: 0,
    toJSON: () => ({}),
    ...overrides,
  };
}

describe("AutoHeight", () => {
  afterEach(() => {
    jest.restoreAllMocks();
  });

  describe("rendering", () => {
    it("should render children inside the container div", () => {
      const { container } = render(
        <AutoHeight>
          <span>content</span>
        </AutoHeight>,
      );
      expect((container.firstChild as HTMLElement).textContent).toContain(
        "content",
      );
    });

    it("should apply the initial height of 300 to the container div", () => {
      const { container } = render(
        <AutoHeight>
          <div>child</div>
        </AutoHeight>,
      );
      expect((container.firstChild as HTMLElement).style.height).toBe("300px");
    });

    it("should forward HTML attributes and keep the marker class", () => {
      const { container } = render(
        <AutoHeight className="my-class" id="my-id">
          <div>child</div>
        </AutoHeight>,
      );
      const div = container.firstChild as HTMLElement;
      expect(div.className).toContain("my-class");
      expect(div.className).toContain("qip-auto-height");
      expect(div.id).toBe("my-id");
    });

    it("should merge provided style with the height style", () => {
      const { container } = render(
        <AutoHeight style={{ color: "red" }}>
          <div>child</div>
        </AutoHeight>,
      );
      const div = container.firstChild as HTMLElement;
      expect(div.style.color).toBe("red");
      expect(div.style.height).toBe("300px");
    });
  });

  describe("event listeners", () => {
    it("should attach a resize listener to window on mount", () => {
      const spy = jest.spyOn(window, "addEventListener");
      render(
        <AutoHeight>
          <div>child</div>
        </AutoHeight>,
      );
      expect(spy).toHaveBeenCalledWith("resize", expect.any(Function));
    });

    it("should remove the resize listener from window on unmount", () => {
      const spy = jest.spyOn(window, "removeEventListener");
      const { unmount } = render(
        <AutoHeight>
          <div>child</div>
        </AutoHeight>,
      );
      unmount();
      expect(spy).toHaveBeenCalledWith("resize", expect.any(Function));
    });

    it("should attach a transitionend listener to the ant-modal-wrap ancestor when inside one", () => {
      const modalWrap = document.createElement("div");
      modalWrap.className = "ant-modal-wrap";
      document.body.appendChild(modalWrap);
      try {
        const spy = jest.spyOn(modalWrap, "addEventListener");
        render(
          <AutoHeight>
            <div>child</div>
          </AutoHeight>,
          { container: modalWrap },
        );
        expect(spy).toHaveBeenCalledWith("transitionend", expect.any(Function));
      } finally {
        document.body.removeChild(modalWrap);
      }
    });

    it("should remove the transitionend listener from ant-modal-wrap on unmount", () => {
      const modalWrap = document.createElement("div");
      modalWrap.className = "ant-modal-wrap";
      document.body.appendChild(modalWrap);
      try {
        const spy = jest.spyOn(modalWrap, "removeEventListener");
        const { unmount } = render(
          <AutoHeight>
            <div>child</div>
          </AutoHeight>,
          { container: modalWrap },
        );
        unmount();
        expect(spy).toHaveBeenCalledWith("transitionend", expect.any(Function));
      } finally {
        document.body.removeChild(modalWrap);
      }
    });

    it("should not update height on transitionend when no ant-modal-wrap ancestor exists", () => {
      const plainWrap = document.createElement("div");
      document.body.appendChild(plainWrap);
      try {
        render(
          <AutoHeight>
            <div>child</div>
          </AutoHeight>,
          { container: plainWrap },
        );
        const autoHeightDiv = plainWrap.firstChild as HTMLElement;
        const childDiv = autoHeightDiv.children[0] as HTMLElement;

        // Mock rects so calcHeight would update height IF it ran
        jest
          .spyOn(plainWrap, "getBoundingClientRect")
          .mockReturnValue(mockRect({ bottom: 600 }));
        jest
          .spyOn(childDiv, "getBoundingClientRect")
          .mockReturnValue(mockRect({ top: 100 }));

        act(() => {
          plainWrap.dispatchEvent(new Event("transitionend"));
        });

        // Height unchanged: no transitionend listener was attached to plainWrap
        expect(autoHeightDiv.style.height).toBe("300px");
      } finally {
        document.body.removeChild(plainWrap);
      }
    });
  });

  describe("height calculation", () => {
    // Pin an element's measured geometry so calcHeight is deterministic.
    // A viewport contributes its visible bottom (top + clientHeight); the form
    // content contributes its rect bottom (where the content below the editor
    // ends). The two coincide when there is a single scroll container.
    function mockBox(
      el: HTMLElement,
      rect: Partial<DOMRect>,
      clientHeight?: number,
    ): void {
      jest.spyOn(el, "getBoundingClientRect").mockReturnValue(mockRect(rect));
      if (clientHeight !== undefined) {
        Object.defineProperty(el, "clientHeight", {
          configurable: true,
          value: clientHeight,
        });
      }
    }

    it("should size the editor to fill the viewport minus the content below it", () => {
      const modalBody = document.createElement("div");
      modalBody.className = "ant-modal-body";
      document.body.appendChild(modalBody);
      try {
        render(
          <AutoHeight>
            <div>child</div>
          </AutoHeight>,
          { container: modalBody },
        );
        const autoHeightDiv = modalBody.firstChild as HTMLElement;
        const childDiv = autoHeightDiv.children[0] as HTMLElement;

        // viewportBottom = 0 + 600 = 600; belowEditor = 620 - 500 = 120
        // available = 600 - 50 - 120 - 12 = 418
        mockBox(modalBody, { top: 0, bottom: 620 }, 600);
        mockBox(autoHeightDiv, { bottom: 500 });
        mockBox(childDiv, { top: 50 });

        act(() => {
          window.dispatchEvent(new Event("resize"));
        });

        expect(autoHeightDiv.style.height).toBe("418px");
      } finally {
        document.body.removeChild(modalBody);
      }
    });

    it("should clamp the height to the minimum when available space is below it", () => {
      const modalBody = document.createElement("div");
      modalBody.className = "ant-modal-body";
      document.body.appendChild(modalBody);
      try {
        render(
          <AutoHeight>
            <div>child</div>
          </AutoHeight>,
          { container: modalBody },
        );
        const autoHeightDiv = modalBody.firstChild as HTMLElement;
        const childDiv = autoHeightDiv.children[0] as HTMLElement;

        // belowEditor = 400 - 300 = 100; available = 400 - 50 - 100 - 12 = 238 ≤ 300
        mockBox(modalBody, { top: 0, bottom: 400 }, 400);
        mockBox(autoHeightDiv, { bottom: 300 });
        mockBox(childDiv, { top: 50 });

        act(() => {
          window.dispatchEvent(new Event("resize"));
        });

        expect(autoHeightDiv.style.height).toBe("300px");
      } finally {
        document.body.removeChild(modalBody);
      }
    });

    it("should bound the editor by the outer scroll area but measure content from the inner one", () => {
      const modalBody = document.createElement("div");
      modalBody.className = "ant-modal-body";
      const formWrapper = document.createElement("div");
      formWrapper.style.overflowY = "auto";
      const innerForm = document.createElement("div");
      innerForm.style.overflowY = "auto";
      formWrapper.appendChild(innerForm);
      modalBody.appendChild(formWrapper);
      document.body.appendChild(modalBody);
      try {
        render(
          <AutoHeight>
            <div>child</div>
          </AutoHeight>,
          { container: innerForm },
        );
        const autoHeightDiv = innerForm.firstChild as HTMLElement;
        const childDiv = autoHeightDiv.children[0] as HTMLElement;

        // viewport = formWrapper (clientHeight 800); content = innerForm (bottom 760).
        // belowEditor = 760 - 700 = 60; available = 800 - 100 - 60 - 12 = 628
        mockBox(formWrapper, { top: 0, bottom: 800 }, 800);
        mockBox(innerForm, { top: 0, bottom: 760 }, 760);
        mockBox(autoHeightDiv, { bottom: 700 });
        mockBox(childDiv, { top: 100 });

        act(() => {
          window.dispatchEvent(new Event("resize"));
        });

        expect(autoHeightDiv.style.height).toBe("628px");
      } finally {
        document.body.removeChild(modalBody);
      }
    });

    it("should update height on transitionend when inside an ant-modal-wrap", () => {
      const modalWrap = document.createElement("div");
      modalWrap.className = "ant-modal-wrap";
      const modalBody = document.createElement("div");
      modalBody.className = "ant-modal-body";
      modalWrap.appendChild(modalBody);
      document.body.appendChild(modalWrap);
      try {
        render(
          <AutoHeight>
            <div>child</div>
          </AutoHeight>,
          { container: modalBody },
        );
        const autoHeightDiv = modalBody.firstChild as HTMLElement;
        const childDiv = autoHeightDiv.children[0] as HTMLElement;

        // viewportBottom = 700; belowEditor = 700 - 600 = 100
        // available = 700 - 200 - 100 - 12 = 388
        mockBox(modalBody, { top: 0, bottom: 700 }, 700);
        mockBox(autoHeightDiv, { bottom: 600 });
        mockBox(childDiv, { top: 200 });

        act(() => {
          modalWrap.dispatchEvent(new Event("transitionend"));
        });

        expect(autoHeightDiv.style.height).toBe("388px");
      } finally {
        document.body.removeChild(modalWrap);
      }
    });

    it("falls back to the parent element as viewport when there is no modal body", () => {
      const parent = document.createElement("div");
      document.body.appendChild(parent);
      try {
        render(
          <AutoHeight>
            <div>child</div>
          </AutoHeight>,
          { container: parent },
        );
        const autoHeightDiv = parent.firstChild as HTMLElement;
        const childDiv = autoHeightDiv.children[0] as HTMLElement;

        // No ant-modal-body or scroll ancestor: the viewport falls back to the
        // parent. viewportBottom = 0 + 900 = 900; belowEditor = 900 - 800 = 100;
        // available = 900 - 0 - 100 - 12 = 788.
        mockBox(parent, { top: 0, bottom: 900 }, 900);
        mockBox(autoHeightDiv, { bottom: 800 });
        mockBox(childDiv, { top: 0 });

        act(() => {
          window.dispatchEvent(new Event("resize"));
        });

        expect(autoHeightDiv.style.height).toBe("788px");
      } finally {
        document.body.removeChild(parent);
      }
    });
  });
});
