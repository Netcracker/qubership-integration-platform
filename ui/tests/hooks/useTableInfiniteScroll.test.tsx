/**
 * @jest-environment jsdom
 */
import React, { useRef } from "react";
import { render } from "@testing-library/react";
import {
  resetIntersectionObservers,
  triggerIntersection,
} from "../setup/intersection-observer";
import {
  TABLE_LOAD_SENTINEL_TEST_ID,
  useTableInfiniteScroll,
} from "../../src/hooks/useTableInfiniteScroll";

type HarnessProps = {
  isLoading: boolean;
  allLoaded: boolean;
  loadMore: () => void;
};

// The hook appends its sentinel beside the table inside `.ant-table-body`, so the
// harness renders that node rather than a bare div.
const Harness: React.FC<HarnessProps> = ({
  isLoading,
  allLoaded,
  loadMore,
}) => {
  const wrapperRef = useRef<HTMLDivElement>(null);
  useTableInfiniteScroll(wrapperRef, { isLoading, allLoaded, loadMore });
  return (
    <div ref={wrapperRef}>
      <div className="ant-table-body">
        <table />
      </div>
    </div>
  );
};

function sentinel(): HTMLElement | null {
  return document.querySelector(
    `[data-testid="${TABLE_LOAD_SENTINEL_TEST_ID}"]`,
  );
}

beforeEach(() => {
  resetIntersectionObservers();
});

describe("useTableInfiniteScroll", () => {
  it("should ask for the next page when the sentinel comes into view", () => {
    const loadMore = jest.fn();

    render(<Harness isLoading={false} allLoaded={false} loadMore={loadMore} />);

    expect(sentinel()).toBeTruthy();
    triggerIntersection();
    expect(loadMore).toHaveBeenCalledTimes(1);
  });

  it("should ask for nothing while the sentinel stays out of view", () => {
    const loadMore = jest.fn();

    render(<Harness isLoading={false} allLoaded={false} loadMore={loadMore} />);
    triggerIntersection(false);

    expect(loadMore).not.toHaveBeenCalled();
  });

  // Without this guard an intersection that lands mid-request asks again, and the
  // answers keep the sentinel in view: an unbounded request loop.
  it("should ask for nothing while a page is already loading", () => {
    const loadMore = jest.fn();

    render(<Harness isLoading={true} allLoaded={false} loadMore={loadMore} />);

    expect(sentinel()).toBeNull();
    triggerIntersection();
    expect(loadMore).not.toHaveBeenCalled();
  });

  it("should ask for nothing once the list is fully loaded", () => {
    const loadMore = jest.fn();

    render(<Harness isLoading={false} allLoaded={true} loadMore={loadMore} />);

    expect(sentinel()).toBeNull();
    triggerIntersection();
    expect(loadMore).not.toHaveBeenCalled();
  });

  it("should observe again when a finished load reopens the sentinel", () => {
    const loadMore = jest.fn();
    const { rerender } = render(
      <Harness isLoading={true} allLoaded={false} loadMore={loadMore} />,
    );
    expect(sentinel()).toBeNull();

    rerender(
      <Harness isLoading={false} allLoaded={false} loadMore={loadMore} />,
    );
    triggerIntersection();

    expect(loadMore).toHaveBeenCalledTimes(1);
  });

  it("should drop its observer and sentinel when the table goes away", () => {
    const loadMore = jest.fn();
    const { unmount } = render(
      <Harness isLoading={false} allLoaded={false} loadMore={loadMore} />,
    );

    unmount();
    triggerIntersection();

    expect(sentinel()).toBeNull();
    expect(loadMore).not.toHaveBeenCalled();
  });
});
