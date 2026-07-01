import { fireEvent } from "@testing-library/react";

// Antd v6 renders the Select trigger as `.ant-select-content` and dropdown
// options as `.ant-select-item`. Keep those internal class names in one place so
// the next antd rename is a one-line change rather than a multi-file sweep.
const SELECT_TRIGGER = ".ant-select-content";

/** All antd Select triggers within a container. */
export function getSelectTriggers(container: HTMLElement): HTMLElement[] {
  return Array.from(container.querySelectorAll<HTMLElement>(SELECT_TRIGGER));
}

/** The single antd Select trigger within a container. */
export function getSelectTrigger(container: HTMLElement): HTMLElement {
  const trigger = container.querySelector<HTMLElement>(SELECT_TRIGGER);
  if (!trigger) {
    throw new Error(`antd Select trigger (${SELECT_TRIGGER}) not found`);
  }
  return trigger;
}

/** Open a Select's dropdown (v6 opens on mousedown of the trigger). */
export function openSelect(container: HTMLElement): void {
  fireEvent.mouseDown(getSelectTrigger(container));
}

/** A dropdown option by its title, or null if not rendered yet. */
export function querySelectOption(title: string): HTMLElement | null {
  return document.querySelector<HTMLElement>(
    `.ant-select-item[title="${title}"]`,
  );
}
