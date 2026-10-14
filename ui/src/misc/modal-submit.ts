function closeOnSettled(
  result: unknown,
  setLoading: (value: boolean) => void,
  close: () => void,
): void {
  if (result instanceof Promise) {
    void (result as Promise<unknown>).finally(() => {
      setLoading(false);
      close();
    });
  } else {
    setLoading(false);
    close();
  }
}

export function handleModalSubmit(
  submit: () => unknown,
  setLoading: (value: boolean) => void,
  close: () => void,
): void {
  setLoading(true);
  try {
    closeOnSettled(submit(), setLoading, close);
  } catch {
    setLoading(false);
    close();
  }
}
