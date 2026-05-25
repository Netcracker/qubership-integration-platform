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

    it("should forward HTML attributes to the container div", () => {
      const { container } = render(
        <AutoHeight className="my-class" id="my-id">
          <div>child</div>
        </AutoHeight>,
      );
      const div = container.firstChild as HTMLElement;
      expect(div.className).toBe("my-class");
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
        expect(spy).toHaveBeenCalledWith(
          "transitionend",
          expect.any(Function),
        );
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
    it("should update height when resize fires and available space exceeds 300", () => {
      const { container } = render(
        <AutoHeight>
          <div>child</div>
        </AutoHeight>,
      );
      const autoHeightDiv = container.firstChild as HTMLElement;
      const childDiv = autoHeightDiv.children[0] as HTMLElement;

      // scrollParent = el.parentElement = container (no .ant-modal-body ancestor)
      // available = parentRect.bottom - elRect.top - 60 = 600 - 100 - 60 = 440
      jest
        .spyOn(container, "getBoundingClientRect")
        .mockReturnValue(mockRect({ bottom: 600 }));
      jest
        .spyOn(childDiv, "getBoundingClientRect")
        .mockReturnValue(mockRect({ top: 100 }));

      act(() => {
        window.dispatchEvent(new Event("resize"));
      });

      expect(autoHeightDiv.style.height).toBe("440px");
    });

    it("should not update height when resize fires and available space does not exceed 300", () => {
      const { container } = render(
        <AutoHeight>
          <div>child</div>
        </AutoHeight>,
      );
      const autoHeightDiv = container.firstChild as HTMLElement;
      const childDiv = autoHeightDiv.children[0] as HTMLElement;

      // available = 400 - 100 - 60 = 240 ≤ 300
      jest
        .spyOn(container, "getBoundingClientRect")
        .mockReturnValue(mockRect({ bottom: 400 }));
      jest
        .spyOn(childDiv, "getBoundingClientRect")
        .mockReturnValue(mockRect({ top: 100 }));

      act(() => {
        window.dispatchEvent(new Event("resize"));
      });

      expect(autoHeightDiv.style.height).toBe("300px");
    });

    it("should use ant-modal-body as the scroll parent when an ancestor with that class exists", () => {
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

        // scrollParent = el.closest('.ant-modal-body') = modalBody
        // available = 500 - 50 - 60 = 390
        jest
          .spyOn(modalBody, "getBoundingClientRect")
          .mockReturnValue(mockRect({ bottom: 500 }));
        jest
          .spyOn(childDiv, "getBoundingClientRect")
          .mockReturnValue(mockRect({ top: 50 }));

        act(() => {
          window.dispatchEvent(new Event("resize"));
        });

        expect(autoHeightDiv.style.height).toBe("390px");
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

        // available = 700 - 200 - 60 = 440
        jest
          .spyOn(modalBody, "getBoundingClientRect")
          .mockReturnValue(mockRect({ bottom: 700 }));
        jest
          .spyOn(childDiv, "getBoundingClientRect")
          .mockReturnValue(mockRect({ top: 200 }));

        act(() => {
          modalWrap.dispatchEvent(new Event("transitionend"));
        });

        expect(autoHeightDiv.style.height).toBe("440px");
      } finally {
        document.body.removeChild(modalWrap);
      }
    });
  });
});
