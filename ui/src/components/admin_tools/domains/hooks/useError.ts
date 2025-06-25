import { useState, useCallback } from 'react';

export interface ErrorState {
  message: string;
  code?: string;
  details?: unknown;
}

export const useError = () => {
  const [error, setError] = useState<ErrorState | null>(null);

  const setErrorState = useCallback((error: ErrorState) => {
    setError(error);
  }, []);

  const clearError = useCallback(() => {
    setError(null);
  }, []);

  return {
    error,
    setError: setErrorState,
    clearError,
  };
}; 