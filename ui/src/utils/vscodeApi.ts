/**
 * VS Code API Singleton
 * Manages VS Code API instance to avoid "already acquired" errors
 */

class VSCodeApiSingleton {
  private static instance: VSCodeApiSingleton;
  private api: any = null;
  private isInitialized = false;

  private constructor() {}

  public static getInstance(): VSCodeApiSingleton {
    if (!VSCodeApiSingleton.instance) {
      VSCodeApiSingleton.instance = new VSCodeApiSingleton();
    }
    return VSCodeApiSingleton.instance;
  }

  public getApi(): any {
    if (this.api && this.api.postMessage) {
      return this.api;
    }

    if (this.isInitialized) {
      console.warn('VSCodeApiSingleton: API already initialized but not available');
      return null;
    }

    this.initializeApi();
    return this.api;
  }

  private initializeApi(): void {
    if (this.isInitialized) {
      return;
    }

    try {
      console.log('VSCodeApiSingleton: Initializing VS Code API...', {
        hasWindowVscode: !!(window as any).vscode,
        hasAcquireVsCodeApi: !!(window as any).acquireVsCodeApi,
        hasGlobalVscodeApi: !!(window as any).vscodeApi
      });

      // Try to get existing API first
      if ((window as any).vscodeApi) {
        this.api = (window as any).vscodeApi;
        console.log('VSCodeApiSingleton: Using existing global vscodeApi');
      } else if ((window as any).vscode) {
        this.api = (window as any).vscode;
        console.log('VSCodeApiSingleton: Using window.vscode');
        // Store globally for other components
        (window as any).vscodeApi = this.api;
      } else if ((window as any).acquireVsCodeApi) {
        try {
          this.api = (window as any).acquireVsCodeApi();
          console.log('VSCodeApiSingleton: Successfully acquired VS Code API');
          // Store globally for other components
          (window as any).vscodeApi = this.api;
        } catch (e) {
          console.warn('VSCodeApiSingleton: acquireVsCodeApi failed, API already acquired:', e.message);
          // Try to find existing API in other locations
          this.findExistingApi();
        }
      } else {
        console.error('VSCodeApiSingleton: No VS Code API available');
      }

      this.isInitialized = true;

      if (this.api && this.api.postMessage) {
        console.log('VSCodeApiSingleton: API initialized successfully');
      } else {
        console.error('VSCodeApiSingleton: API initialization failed');
      }
    } catch (e) {
      console.error('VSCodeApiSingleton: Error initializing API:', e);
      this.isInitialized = true;
    }
  }

  private findExistingApi(): void {
    console.log('VSCodeApiSingleton: Searching for existing API...');
    
    // Get all window keys for debugging
    const allKeys = Object.keys(window);
    const vscodeKeys = allKeys.filter(k => k.toLowerCase().includes('vscode') || k.toLowerCase().includes('acquire'));
    console.log('VSCodeApiSingleton: Available keys:', { allKeys: allKeys.slice(0, 10), vscodeKeys });
    
    // Check if there are any functions that might return the API
    const functionKeys = allKeys.filter(k => typeof (window as any)[k] === 'function');
    console.log('VSCodeApiSingleton: Function keys:', functionKeys.slice(0, 5));
    
    // Search for any object with postMessage method
    console.log('VSCodeApiSingleton: Searching for objects with postMessage...');
    for (const key of allKeys) {
      try {
        const value = (window as any)[key];
        if (value && typeof value === 'object' && typeof value.postMessage === 'function') {
          console.log(`VSCodeApiSingleton: Found object with postMessage at window.${key}:`, {
            hasPostMessage: true,
            type: typeof value
          });
          
          // Check if this looks like VS Code API (avoid accessing properties that might cause security errors)
          if (value.postMessage && key.toLowerCase().includes('vscode')) {
            this.api = value;
            (window as any).vscodeApi = this.api;
            console.log(`VSCodeApiSingleton: Found VS Code API at window.${key}`);
            return;
          }
        }
      } catch (e) {
        // Skip objects that cause security errors
        console.warn(`VSCodeApiSingleton: Skipping window.${key} due to security error:`, e.message);
      }
    }
    
    // Check common locations where API might be stored
    const possibleLocations = [
      'vscodeApi',
      '__vscodeApi',
      'vscode',
      '__vscode',
      'acquireVsCodeApi'
    ];

    for (const location of possibleLocations) {
      const value = (window as any)[location];
      console.log(`VSCodeApiSingleton: Checking window.${location}:`, { 
        exists: !!value, 
        hasPostMessage: !!(value && value.postMessage),
        type: typeof value
      });
      
      if (value && value.postMessage) {
        this.api = value;
        (window as any).vscodeApi = this.api;
        console.log(`VSCodeApiSingleton: Found existing API at window.${location}`);
        return;
      }
    }

    // Check if acquireVsCodeApi is a function that returns the API
    if ((window as any).acquireVsCodeApi && typeof (window as any).acquireVsCodeApi === 'function') {
      try {
        // Try to get the API without calling acquireVsCodeApi again
        console.log('VSCodeApiSingleton: acquireVsCodeApi is available but already called');
        // Check if there's a way to get the existing instance
        const apiInstance = (window as any).acquireVsCodeApi;
        if (apiInstance && apiInstance.postMessage) {
          this.api = apiInstance;
          (window as any).vscodeApi = this.api;
          console.log('VSCodeApiSingleton: Found API instance from acquireVsCodeApi');
          return;
        }
      } catch (e) {
        console.warn('VSCodeApiSingleton: Error checking acquireVsCodeApi:', e.message);
      }
    }

    // Check globalThis
    if ((globalThis as any).vscode && (globalThis as any).vscode.postMessage) {
      this.api = (globalThis as any).vscode;
      (window as any).vscodeApi = this.api;
      console.log('VSCodeApiSingleton: Found existing API in globalThis.vscode');
      return;
    }

    // Check if there's a global variable set by the extension
    const globalVars = ['vscode', 'vscodeApi', '__vscode', '__vscodeApi'];
    for (const varName of globalVars) {
      if ((globalThis as any)[varName] && (globalThis as any)[varName].postMessage) {
        this.api = (globalThis as any)[varName];
        (window as any).vscodeApi = this.api;
        console.log(`VSCodeApiSingleton: Found existing API in globalThis.${varName}`);
        return;
      }
    }

    // Last resort: try to find API in the extension's context
    try {
      // Check if there's a way to access the API through the extension's context
      const extensionContext = (window as any).extensionContext || (globalThis as any).extensionContext;
      if (extensionContext && extensionContext.vscode && extensionContext.vscode.postMessage) {
        this.api = extensionContext.vscode;
        (window as any).vscodeApi = this.api;
        console.log('VSCodeApiSingleton: Found API in extension context');
        return;
      }
    } catch (e) {
      console.warn('VSCodeApiSingleton: Error checking extension context:', e.message);
    }

    // Try to find API in the webview's parent context
    try {
      if (window.parent && window.parent !== window) {
        const parentVscode = (window.parent as any).vscode;
        if (parentVscode && parentVscode.postMessage) {
          this.api = parentVscode;
          (window as any).vscodeApi = this.api;
          console.log('VSCodeApiSingleton: Found API in parent window');
          return;
        }
      }
    } catch (e) {
      console.warn('VSCodeApiSingleton: Error checking parent window:', e.message);
    }

    // Try to find API in all global variables (with security error handling)
    console.log('VSCodeApiSingleton: Searching all global variables...');
    try {
      // Check if there's a global variable that might contain the API
      const globalVars = ['vscode', 'vscodeApi', '__vscode', '__vscodeApi', 'acquireVsCodeApi'];
      for (const varName of globalVars) {
        try {
          if ((globalThis as any)[varName]) {
            const value = (globalThis as any)[varName];
            console.log(`VSCodeApiSingleton: Checking globalThis.${varName}:`, {
              exists: !!value,
              type: typeof value,
              hasPostMessage: !!(value && value.postMessage)
            });
            
            if (value && value.postMessage) {
              this.api = value;
              (window as any).vscodeApi = this.api;
              console.log(`VSCodeApiSingleton: Found API in globalThis.${varName}`);
              return;
            }
          }
        } catch (e) {
          console.warn(`VSCodeApiSingleton: Security error checking globalThis.${varName}:`, e.message);
        }
      }
    } catch (e) {
      console.warn('VSCodeApiSingleton: Error checking global variables:', e.message);
    }

    // Try to create a new API instance if none found
    console.log('VSCodeApiSingleton: Attempting to create new API instance...');
    try {
      // Check if we can get a fresh API instance
      if ((window as any).acquireVsCodeApi && typeof (window as any).acquireVsCodeApi === 'function') {
        // Try to get a new instance (this might fail if already acquired)
        try {
          const newApi = (window as any).acquireVsCodeApi();
          if (newApi && newApi.postMessage) {
            this.api = newApi;
            (window as any).vscodeApi = this.api;
            console.log('VSCodeApiSingleton: Successfully created new API instance');
            return;
          }
        } catch (e) {
          console.warn('VSCodeApiSingleton: Cannot create new API instance:', e.message);
          // If API is already acquired, try to find it in a different way
          console.log('VSCodeApiSingleton: API already acquired, trying alternative approach...');
          
          // Try to find the API in the extension's context
          try {
            // Check if there's a way to access the existing API
            const extensionContext = (window as any).extensionContext;
            if (extensionContext && extensionContext.vscode && extensionContext.vscode.postMessage) {
              this.api = extensionContext.vscode;
              (window as any).vscodeApi = this.api;
              console.log('VSCodeApiSingleton: Found API in extension context');
              return;
            }
          } catch (contextError) {
            console.warn('VSCodeApiSingleton: Error checking extension context:', contextError.message);
          }
          
          // Try to find API in other common locations
          console.log('VSCodeApiSingleton: Trying to find API in other locations...');
          
          // Check if there's a global variable that might contain the API
          const possibleGlobals = ['vscode', 'vscodeApi', '__vscode', '__vscodeApi'];
          for (const globalName of possibleGlobals) {
            try {
              if ((window as any)[globalName] && (window as any)[globalName].postMessage) {
                this.api = (window as any)[globalName];
                (window as any).vscodeApi = this.api;
                console.log(`VSCodeApiSingleton: Found API in window.${globalName}`);
                return;
              }
            } catch (e) {
              console.warn(`VSCodeApiSingleton: Error checking window.${globalName}:`, e.message);
            }
          }
          
          // Check if there's a way to get the API from the extension's webview context
          try {
            // Try to access the API through the extension's webview API
            if ((window as any).vscodeWebview && (window as any).vscodeWebview.postMessage) {
              this.api = (window as any).vscodeWebview;
              (window as any).vscodeApi = this.api;
              console.log('VSCodeApiSingleton: Found API in vscodeWebview');
              return;
            }
          } catch (e) {
            console.warn('VSCodeApiSingleton: Error checking vscodeWebview:', e.message);
          }
        }
      }
    } catch (e) {
      console.warn('VSCodeApiSingleton: Error creating new API instance:', e.message);
    }

    // Last resort: try to find API in any possible location
    console.log('VSCodeApiSingleton: Last resort - searching all possible locations...');
    
    // Check all possible global variables that might contain the API
    const possibleGlobals = [
      'vscode', 'vscodeApi', '__vscode', '__vscodeApi',
      'webviewContext', 'webviewApi', 'extensionContext',
      'vscodeWebview', 'webview', 'extension'
    ];
    
    for (const globalName of possibleGlobals) {
      try {
        const value = (window as any)[globalName];
        if (value && value.postMessage) {
          this.api = value;
          (window as any).vscodeApi = this.api;
          console.log(`VSCodeApiSingleton: Found API in window.${globalName}`);
          return;
        }
        
        // Check if it's an object with nested vscode property
        if (value && typeof value === 'object' && value.vscode && value.vscode.postMessage) {
          this.api = value.vscode;
          (window as any).vscodeApi = this.api;
          console.log(`VSCodeApiSingleton: Found API in window.${globalName}.vscode`);
          return;
        }
      } catch (e) {
        console.warn(`VSCodeApiSingleton: Error checking window.${globalName}:`, e.message);
      }
    }
    
    console.warn('VSCodeApiSingleton: No existing API found in any location');
  }

  public isAvailable(): boolean {
    return this.api && this.api.postMessage;
  }

  public sendMessage(message: any): void {
    if (this.isAvailable()) {
      this.api.postMessage(message);
      console.log('VSCodeApiSingleton: Message sent:', message);
    } else {
      console.error('VSCodeApiSingleton: Cannot send message, API not available');
    }
  }

  public reset(): void {
    this.api = null;
    this.isInitialized = false;
    console.log('VSCodeApiSingleton: Reset');
  }

  public forceFindApi(): boolean {
    console.log('VSCodeApiSingleton: Force searching for API...');
    
    // Reset and try to find API again
    this.reset();
    this.findExistingApi();
    
    if (this.api && this.api.postMessage) {
      console.log('VSCodeApiSingleton: API found after force search');
      return true;
    }
    
    console.warn('VSCodeApiSingleton: API not found after force search');
    return false;
  }
}

export default VSCodeApiSingleton;
