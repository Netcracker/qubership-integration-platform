import React, { useEffect, useState, useMemo, useCallback } from "react";
import { useParams, useLocation } from "react-router-dom";
import { Spin, Result, Button } from "antd";
import { DocumentationViewer } from "../components/documentation/DocumentationViewer";
import { DocumentationSidebar } from "../components/documentation/DocumentationSidebar";
import { DocumentationSearch } from "../components/documentation/DocumentationSearch";
import { useDocumentation } from "../hooks/useDocumentation";
import { useNavigate } from "react-router-dom";
import {
  DOCUMENTATION_ASSETS_BASE_URL,
  DOCUMENTATION_ROUTE_BASE,
  joinUrl,
  toDocMarkdownAssetPath,
  toDocRoutePath,
} from "../services/documentation/documentationUrlUtils";
import { PageWithSidebar } from "./PageWithSidebar";
import styles from "./DocumentationPage.module.css";

export const DocumentationPage: React.FC = () => {
  const { "*": docPath } = useParams<{ "*": string }>();
  const location = useLocation();
  const navigate = useNavigate();
  const [content, setContent] = useState<string>("");
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const { loadPaths } = useDocumentation();

  useEffect(() => {
    const loadDocument = async () => {
      setIsLoading(true);
      setError(null);

      try {
        const routeBase = DOCUMENTATION_ROUTE_BASE;
        const assetsBaseUrl = DOCUMENTATION_ASSETS_BASE_URL;

        // Handle search page
        if (location.pathname === `${routeBase}/search`) {
          // Search is handled by DocumentationSearch component
          setIsLoading(false);
          return;
        }

        // Handle not-found page
        if (docPath === "not-found") {
          setContent(
            "# Page Not Found\n\nThe requested documentation page could not be found.",
          );
          setIsLoading(false);
          return;
        }

        // Convert doc path to markdown file path
        // Paths from mapping start with /doc/, we need to convert them to actual file paths
        const markdownPath = toDocMarkdownAssetPath(docPath || "");

        // If no path specified, load default document
        if (!docPath) {
          const paths: string[] = await loadPaths();
          if (paths.length > 0) {
            void navigate(
              toDocRoutePath(routeBase, paths[0].replace(/\.md$/, "")),
              {
                replace: true,
              },
            );
            return;
          }
        }

        // Load markdown file
        const filePath = joinUrl(assetsBaseUrl, markdownPath);
        const response = await fetch(filePath);

        if (!response.ok) {
          if (response.status === 404) {
            setError("Document not found");
          } else {
            setError(`Failed to load document: ${response.statusText}`);
          }
          setIsLoading(false);
          return;
        }

        const text = await response.text();
        setContent(text);
      } catch (err: unknown) {
        console.error("Failed to load documentation:", err);
        setError(err instanceof Error ? err.message : "Unknown error");
      } finally {
        setIsLoading(false);
      }
    };

    void loadDocument();
  }, [docPath, location.pathname, navigate, loadPaths]);

  const handleTOCSelect = useCallback(
    (path: string) => {
      void navigate(path);
    },
    [navigate],
  );

  const sidebar = useMemo(
    () => (
      <>
        <DocumentationSearch onSelect={handleTOCSelect} />
        <DocumentationSidebar onSelect={handleTOCSelect} />
      </>
    ),
    [handleTOCSelect],
  );

  if (isLoading) {
    return (
      <div className={styles.loadingContainer}>
        <Spin size="large" />
      </div>
    );
  }

  if (error) {
    return (
      <Result
        status="404"
        title="Document Not Found"
        subTitle={error}
        extra={
          <Button
            type="primary"
            onClick={() => {
              void navigate(DOCUMENTATION_ROUTE_BASE);
            }}
          >
            Go to Documentation Home
          </Button>
        }
      />
    );
  }

  return (
    <PageWithSidebar
      sidebar={sidebar}
      sidebarWidth={300}
      sidebarCollapsedWidth={80}
      showDivider={false}
    >
      <DocumentationViewer content={content} docPath={docPath ?? ""} />
    </PageWithSidebar>
  );
};
