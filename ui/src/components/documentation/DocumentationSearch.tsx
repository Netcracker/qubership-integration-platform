import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { Input, List, Typography, Spin } from 'antd';
import { SearchOutlined } from '@ant-design/icons';
import { useDocumentation } from '../../hooks/useDocumentation';
import type {
  HighlightSegment,
  SearchResult,
} from '../../services/documentation/documentationTypes';
import { useNavigate } from 'react-router-dom';
import {
  DOCUMENTATION_ROUTE_BASE,
  toDocRoutePath,
} from '../../services/documentation/documentationUrlUtils';
import {
  createDebouncedCallback,
  createLatestOnlyGuard,
} from '../../services/documentation/documentationAsyncUtils';

const { Text } = Typography;

interface DocumentationSearchProps {
  onSelect?: (path: string) => void;
}

export const DocumentationSearch: React.FC<DocumentationSearchProps> = ({ onSelect }) => {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<SearchResult[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [searchDetails, setSearchDetails] = useState<
    Record<number, HighlightSegment[][]>
  >({});
  const { search, getSearchDetailSegments, loadNames, loadPaths } =
    useDocumentation();
  const navigate = useNavigate();
  const [titlesByRef, setTitlesByRef] = useState<Record<number, string>>({});
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

        setResults(searchResults);

        const nextTitlesByRef: Record<number, string> = {};
        for (const result of searchResults.slice(0, 50)) {
          const title = names[result.ref]?.[names[result.ref].length - 1];
          if (title) {
            nextTitlesByRef[result.ref] = title;
          }
        }
        setTitlesByRef(nextTitlesByRef);

        const topResults = searchResults.slice(0, 5);
        const detailPromises = topResults.map(async (result) => {
          const detail = await getSearchDetailSegments(result.ref, trimmed);
          return { ref: result.ref, detail };
        });

        const settled = await Promise.allSettled(detailPromises);
        if (!latestOnly.isLatest(token)) {
          return;
        }

        const details: Record<number, HighlightSegment[][]> = {};
        for (const item of settled) {
          if (item.status === 'fulfilled') {
            details[item.value.ref] = item.value.detail;
          }
        }
        setSearchDetails(details);
      } catch (error) {
        if (!latestOnly.isLatest(token)) {
          return;
        }
        console.error('Search failed:', error);
        setResults([]);
        setSearchDetails({});
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
      setIsLoading(false);
      return;
    }

    const token = latestOnly.nextToken();
    setIsLoading(true);
    debouncedSearch.call(token, trimmed);

    return () => {
      debouncedSearch.cancel();
    };
  }, [debouncedSearch, latestOnly, query]);

  const handleQueryChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const value = e.target.value;
    setQuery(value);
  };

  const handleResultClick = async (ref: number) => {
    try {
      const paths = await loadPaths();
      const rawPath = paths[ref];
      if (!rawPath) {
        return;
      }

      const routeBase = DOCUMENTATION_ROUTE_BASE;
      const path = toDocRoutePath(routeBase, rawPath.replace(/\.md$/, ''));
      if (onSelect) {
        onSelect(path);
      } else {
        void navigate(path);
      }
    } catch (error) {
      console.error('Failed to get document path:', error);
    }
  };

  return (
    <div style={{ padding: '16px' }}>
      <Input
        placeholder="Search documentation..."
        prefix={<SearchOutlined />}
        value={query}
        onChange={handleQueryChange}
        allowClear
      />
      {isLoading && (
        <div style={{ textAlign: 'center', padding: '16px' }}>
          <Spin />
        </div>
      )}
      {!isLoading && results.length > 0 && (
        <List
          style={{ marginTop: '16px' }}
          dataSource={results}
          renderItem={(item) => {
            const details = searchDetails[item.ref] || [];
            const title = titlesByRef[item.ref] ?? `Document #${item.ref}`;
            return (
              <List.Item
                style={{ cursor: 'pointer' }}
                onClick={() => {
                  void handleResultClick(item.ref);
                }}
              >
                <List.Item.Meta
                  title={<Text strong>{title}</Text>}
                  description={
                    <div>
                      {details.map((fragment, idx) => (
                        <div key={idx} style={{ marginBottom: '8px' }}>
                          {fragment.map((seg, segIdx) => (
                            <span
                              key={segIdx}
                              style={{ fontWeight: seg.isHit ? 600 : 400 }}
                            >
                              {seg.text}
                            </span>
                          ))}
                        </div>
                      ))}
                    </div>
                  }
                />
              </List.Item>
            );
          }}
        />
      )}
      {!isLoading && query && results.length === 0 && (
        <div style={{ padding: '16px', textAlign: 'center' }}>
          <Text type="secondary">No results found</Text>
        </div>
      )}
    </div>
  );
};
