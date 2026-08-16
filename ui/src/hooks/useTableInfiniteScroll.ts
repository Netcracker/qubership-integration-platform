import { type RefObject, useEffect } from "react";

export const TABLE_LOAD_SENTINEL_TEST_ID = "table-load-sentinel";

export type UseTableInfiniteScrollOptions = {
  isLoading: boolean;
  allLoaded: boolean;
  loadMore: () => void;
};

/**
 * Fetches the next page once the bottom of a scrolling table comes into view.
 *
 * The sentinel is appended beside `<table>` inside `.ant-table-body` rather than
 * as a row: a sticky antd table sizes its header and body from one shared
 * colgroup, and an extra cell would desynchronize them.
 */
export function useTableInfiniteScroll(
  wrapperRef: RefObject<HTMLDivElement | null>,
  { isLoading, allLoaded, loadMore }: UseTableInfiniteScrollOptions,
): void {
  useEffect(() => {
    if (isLoading || allLoaded) {
      return;
    }
    const root = wrapperRef.current?.querySelector(".ant-table-body");
    if (!root) {
      return;
    }

    const sentinel = document.createElement("div");
    sentinel.dataset.testid = TABLE_LOAD_SENTINEL_TEST_ID;
    sentinel.style.height = "1px";
    root.appendChild(sentinel);

    const observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting)) {
          loadMore();
        }
      },
      { root, rootMargin: "200px" },
    );
    observer.observe(sentinel);

    return () => {
      observer.disconnect();
      sentinel.remove();
    };
  }, [wrapperRef, isLoading, allLoaded, loadMore]);
}
