import React, { useCallback, useEffect, useMemo, useState } from "react";
import { Input, List, Typography, Spin } from "antd";
import { SearchOutlined, FileTextOutlined } from "@ant-design/icons";
import { useDocumentation } from "../../hooks/useDocumentation";
import type {
  HighlightSegment,
  SearchResult,
} from "../../services/documentation/documentationTypes";
import { useNavigate } from "react-router-dom";
import {
  DOCUMENTATION_ROUTE_BASE,
  toDocRoutePath,
} from "../../services/documentation/documentationUrlUtils";
import {
  createDebouncedCallback,
  createLatestOnlyGuard,
} from "../../services/documentation/documentationAsyncUtils";
import styles from "./DocumentationSearch.module.css";

const { Text } = Typography;

const MAX_DISPLAYED_RESULTS = 10;

interface DocumentationSearchProps {
  onSelect?: (path: string) => void;
  /** Called when the search becomes active (has query) or inactive (empty query). */
  onSearchActiveChange?: (active: boolean) => void;
}

export const DocumentationSearch: React.FC<DocumentationSearchProps> = ({
  onSelect,
  onSearchActiveChange,
}) => {
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<SearchResult[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [searchDetails, setSearchDetails] = useState<
    Record<number, HighlightSegment[][]>
  >({});
  const { search, getSearchDetailSegments, loadNames, loadPaths } =
    useDocumentation();
  const navigate = useNavigate();
  const [titlesByRef, setTitlesByRef] = useState<Record<number, string>>({});
  const [breadcrumbsByRef, setBreadcrumbsByRef] = useState<
    Record<number, string>
  >({});
  const [didYouMean, setDidYouMean] = useState<string | null>(null);
  const latestOnly = useMemo(() => createLatestOnlyGuard(), []);

  const runSearch = useCallback(
    async (token: number, trimmed: string) => {
      try {
        const [searchResults, names] = await Promise.all([
          search(trimmed),
          loadNames(),
        ]);

        if (!latestOnly.isLatest(token)) {
          return;
        }

        const limited = searchResults.slice(0, MAX_DISPLAYED_RESULTS);
        setResults(limited);

        const nextTitlesByRef: Record<number, string> = {};
        const nextBreadcrumbsByRef: Record<number, string> = {};
        for (const result of limited) {
          const nameParts = names[result.ref];
          if (nameParts?.length) {
            nextTitlesByRef[result.ref] = nameParts[nameParts.length - 1];
            if (nameParts.length > 1) {
              nextBreadcrumbsByRef[result.ref] = nameParts
                .slice(0, -1)
                .join(" → ");
            }
          }
        }
        setTitlesByRef(nextTitlesByRef);
        setBreadcrumbsByRef(nextBreadcrumbsByRef);

        // Compute "did you mean" suggestion: only for genuine typos,
        // not for normal prefix searches. A query word is a typo when
        // no matched term starts with it (prefix) or equals it (exact).
        const queryWords = trimmed.toLowerCase().split(/\W+/).filter(Boolean);
        const allTerms = [...new Set(limited.flatMap((r) => r.terms))];
        const typoCorrections: string[] = [];
        for (const qw of queryWords) {
          const isPrefixOrExact = allTerms.some(
            (t) => t === qw || t.startsWith(qw),
          );
          if (!isPrefixOrExact) {
            // Find the term that fuzzy-matched this query word
            const correction = allTerms.find(
              (t) => !queryWords.includes(t) && !typoCorrections.includes(t),
            );
            if (correction) {
              typoCorrections.push(correction);
            }
          }
        }
        setDidYouMean(
          typoCorrections.length > 0 ? typoCorrections.join(" ") : null,
        );

        const detailPromises = limited.map(async (result) => {
          const detail = await getSearchDetailSegments(
            result.ref,
            trimmed,
            result.terms,
          );
          return { ref: result.ref, detail };
        });

        const settled = await Promise.allSettled(detailPromises);
        if (!latestOnly.isLatest(token)) {
          return;
        }

        const details: Record<number, HighlightSegment[][]> = {};
        for (const item of settled) {
          if (item.status === "fulfilled") {
            details[item.value.ref] = item.value.detail;
          }
        }
        setSearchDetails(details);
      } catch (error) {
        if (!latestOnly.isLatest(token)) {
          return;
        }
        console.error("Search failed:", error);
        setResults([]);
        setSearchDetails({});
        setTitlesByRef({});
        setBreadcrumbsByRef({});
        setDidYouMean(null);
      } finally {
        if (latestOnly.isLatest(token)) {
          setIsLoading(false);
        }
      }
    },
    [getSearchDetailSegments, latestOnly, loadNames, search],
  );

  const debouncedSearch = useMemo(
    () =>
      createDebouncedCallback<[number, string]>(250, (token, trimmed) => {
        void runSearch(token, trimmed);
      }),
    [runSearch],
  );

  useEffect(() => {
    const trimmed = query.trim();
    if (!trimmed) {
      setResults([]);
      setSearchDetails({});
      setTitlesByRef({});
      setBreadcrumbsByRef({});
      setDidYouMean(null);
      setIsLoading(false);
      onSearchActiveChange?.(false);
      return;
    }

    onSearchActiveChange?.(true);
    const token = latestOnly.nextToken();
    setIsLoading(true);
    debouncedSearch.call(token, trimmed);

    return () => {
      debouncedSearch.cancel();
    };
  }, [debouncedSearch, latestOnly, query, onSearchActiveChange]);

  const handleResultClick = useCallback(
    async (ref: number) => {
      try {
        const paths = await loadPaths();
        const rawPath = paths[ref];
        if (!rawPath) {
          return;
        }

        const path = toDocRoutePath(
          DOCUMENTATION_ROUTE_BASE,
          rawPath.replace(/\.md$/, ""),
        );
        if (onSelect) {
          onSelect(path);
        } else {
          void navigate(path);
        }
      } catch (error) {
        console.error("Failed to get document path:", error);
      }
    },
    [loadPaths, navigate, onSelect],
  );

  // Filter out results without titles
  const displayResults = useMemo(
    () => results.filter((item) => titlesByRef[item.ref]),
    [results, titlesByRef],
  );

  return (
    <div className={styles.container}>
      <Input
        placeholder="Search documentation..."
        prefix={<SearchOutlined />}
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        allowClear
        size="small"
      />
      {isLoading && (
        <div className={styles.loading}>
          <Spin size="small" />
        </div>
      )}
      {!isLoading && didYouMean && (
        <div className={styles.didYouMean}>
          <Text type="secondary">
            Did you mean:{" "}
            <Text
              italic
              className={styles.didYouMeanTerm}
              onClick={() => setQuery(didYouMean)}
            >
              {didYouMean}
            </Text>
            ?
          </Text>
        </div>
      )}
      {!isLoading && displayResults.length > 0 && (
        <List
          className={styles.results}
          dataSource={displayResults}
          split={false}
          size="small"
          renderItem={(item) => {
            const details = searchDetails[item.ref] || [];
            const title = titlesByRef[item.ref];
            return (
              <List.Item
                className={styles.resultItem}
                onClick={() => {
                  void handleResultClick(item.ref);
                }}
              >
                <div className={styles.resultContent}>
                  <div className={styles.resultTitle}>
                    <FileTextOutlined className={styles.resultIcon} />
                    <div className={styles.resultTitleText}>
                      <Text strong ellipsis title={title}>
                        {title}
                      </Text>
                      {breadcrumbsByRef[item.ref] && (
                        <Text
                          type="secondary"
                          className={styles.breadcrumb}
                          ellipsis
                          title={breadcrumbsByRef[item.ref]}
                        >
                          {breadcrumbsByRef[item.ref]}
                        </Text>
                      )}
                    </div>
                  </div>
                  {details.length > 0 && (
                    <div className={styles.snippets}>
                      {details.map((fragment, idx) => (
                        <div key={idx} className={styles.snippet}>
                          {fragment.map((seg, segIdx) => (
                            <span
                              key={segIdx}
                              className={seg.isHit ? styles.hit : undefined}
                            >
                              {seg.text}
                            </span>
                          ))}
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              </List.Item>
            );
          }}
        />
      )}
      {!isLoading && query.trim() && displayResults.length === 0 && (
        <div className={styles.emptyState}>
          <Text type="secondary">No results found</Text>
        </div>
      )}
    </div>
  );
};
