import React from "react";
import { useNavigate } from "react-router";

export type RowLinkProps = {
  /** In-app destination. */
  to: string;
  style?: React.CSSProperties;
  children: React.ReactNode;
};

/**
 * A link rendered inside a row or a panel that is itself clickable. It stops its
 * own click from reaching that handler, so following the link does not also open
 * the row's details.
 *
 * The href is what makes it a link rather than a styled span: an anchor without
 * one takes no focus, does not answer Enter, and is announced to a screen reader
 * as plain text. Routing still goes through the navigator, so a plain click does
 * not reload the application, while a click the browser treats as "open
 * elsewhere" is left to it.
 */
export const RowLink: React.FC<RowLinkProps> = ({ to, style, children }) => {
  const navigate = useNavigate();
  return (
    <a
      href={to}
      style={style}
      onClick={(event) => {
        event.stopPropagation();
        if (
          event.button !== 0 ||
          event.metaKey ||
          event.ctrlKey ||
          event.shiftKey ||
          event.altKey
        ) {
          return;
        }
        event.preventDefault();
        void navigate(to);
      }}
    >
      {children}
    </a>
  );
};
