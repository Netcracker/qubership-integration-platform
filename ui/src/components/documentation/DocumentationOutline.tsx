import React, {
  RefObject,
  useCallback,
  useEffect,
  useMemo,
  useRef,
} from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { useDocumentOutline } from "../../hooks/useDocumentOutline";
import styles from "./DocumentationOutline.module.css";

interface DocumentationOutlineProps {
  viewerRef: RefObject<HTMLDivElement | null>;
  content: string;
}

export const DocumentationOutline: React.FC<DocumentationOutlineProps> = ({
  viewerRef,
  content,
}) => {
  const navigate = useNavigate();
  const location = useLocation();
  const { headings, activeId } = useDocumentOutline(viewerRef, content);
  const activeItemRef = useRef<HTMLAnchorElement | null>(null);
  const outlineRef = useRef<HTMLElement | null>(null);

  const setActiveItemRef = useCallback((el: HTMLAnchorElement | null) => {
    activeItemRef.current = el;
  }, []);

  // scrollIntoView() propagates to all ancestor scroll containers and would
  // fight the user's own scrolling — scroll only the outline sidebar manually.
  useEffect(() => {
    const item = activeItemRef.current;
    const outline = outlineRef.current;
    if (!item || !outline) return;
    const itemRect = item.getBoundingClientRect();
    const outlineRect = outline.getBoundingClientRect();
    if (itemRect.top < outlineRect.top) {
      outline.scrollTop -= outlineRect.top - itemRect.top + 8;
    } else if (itemRect.bottom > outlineRect.bottom) {
      outline.scrollTop += itemRect.bottom - outlineRect.bottom + 8;
    }
  }, [activeId]);

  const minLevel = useMemo(
    () => (headings.length ? Math.min(...headings.map((h) => h.level)) : 0),
    [headings],
  );

  if (headings.length < 2) return null;

  return (
    <aside ref={outlineRef} className={styles.outline}>
      <p className={styles.title}>On this page</p>
      <nav aria-label="Page outline">
        <ul className={styles.list}>
          {headings.map((h) => (
            <li
              key={h.id}
              className={styles.item}
              style={{ paddingLeft: `${(h.level - minLevel) * 12}px` }}
            >
              <a
                ref={h.id === activeId ? setActiveItemRef : undefined}
                className={
                  h.id === activeId
                    ? `${styles.link} ${styles.active}`
                    : styles.link
                }
                href={`#${h.id}`}
                onClick={(e) => {
                  e.preventDefault();
                  void navigate(`${location.pathname}#${h.id}`);
                }}
              >
                {h.text}
              </a>
            </li>
          ))}
        </ul>
      </nav>
    </aside>
  );
};
