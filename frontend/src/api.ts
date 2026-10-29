export interface RuntimeConfig {
  apiBaseUrl?: string;
  backendBaseUrl?: string;
  services?: string[];
}

export interface BackendItem {
  id: string;
  name: string;
  created_at?: string;
}

export interface Customer {
  id: string;
  name?: string;
  db_name?: string;
  dbName?: string;
  created_at?: string;
}

export interface Tenant {
  id: string;
  customer_id?: string;
  schema_name?: string;
  schemaName?: string;
  created_at?: string;
}

let cachedConfig: RuntimeConfig | null = null;

export async function loadConfig(): Promise<RuntimeConfig> {
  if (cachedConfig) return cachedConfig;
  let cfg: RuntimeConfig = {};
  try {
    const res = await fetch("/config.json", { cache: "no-store" });
    if (res.ok) cfg = await res.json();
  } catch {
    // ignore; fall back to env / same-origin
  }
  if (!cfg.apiBaseUrl) {
    const envBase = (import.meta as any).env?.VITE_API_BASE_URL;
    if (envBase) cfg.apiBaseUrl = String(envBase);
  }
  if (!cfg.services || cfg.services.length === 0) {
    cfg.services = ["order-service", "inventory-service"];
  }
  if (!cfg.backendBaseUrl) {
    const envBackend = (import.meta as any).env?.VITE_BACKEND_BASE_URL;
    if (envBackend) cfg.backendBaseUrl = String(envBackend);
  }
  cachedConfig = cfg;
  return cfg;
}

function baseUrl(cfg: RuntimeConfig): string {
  const b = cfg.apiBaseUrl || "";
  return b.replace(/\/+$/, "");
}

export function backendBase(cfg: RuntimeConfig): string {
  let b = cfg.backendBaseUrl || cfg.apiBaseUrl || "";
  b = b.replace(/\/+$/, "");
  // derive backend host from a provisioner host when no explicit base is given
  if (!cfg.backendBaseUrl && b) {
    b = b.replace(/provisioner\./i, "backend.");
  }
  return b;
}

async function backendRequest<T>(
  cfg: RuntimeConfig,
  path: string,
  init?: RequestInit
): Promise<T> {
  const url = backendBase(cfg) + path;
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
    ...(init?.headers as Record<string, string> | undefined),
  };
  const res = await fetch(url, { ...init, headers });
  const text = await res.text();
  let data: any = null;
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = text;
    }
  }
  if (!res.ok) {
    const msg =
      (data && (data.error || data.message)) ||
      (typeof data === "string" ? data : "") ||
      `HTTP ${res.status}`;
    throw new ApiError(res.status, msg);
  }
  return (data === null ? (undefined as unknown as T) : (data as T));
}

export class ApiError extends Error {
  status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

async function request<T>(
  cfg: RuntimeConfig,
  path: string,
  token: string,
  init?: RequestInit
): Promise<T> {
  const url = baseUrl(cfg) + path;
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
    ...(init?.headers as Record<string, string> | undefined),
  };
  if (token) headers["X-Admin-Token"] = token;
  const res = await fetch(url, { ...init, headers });
  const text = await res.text();
  let data: any = null;
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = text;
    }
  }
  if (!res.ok) {
    const msg =
      (data && (data.error || data.message)) ||
      (typeof data === "string" ? data : "") ||
      `HTTP ${res.status}`;
    throw new ApiError(res.status, msg);
  }
  return data as T;
}

export async function listCustomers(cfg: RuntimeConfig, token: string): Promise<Customer[]> {
  return request<Customer[]>(cfg, "/api/customers", token, { method: "GET" });
}

export async function createCustomer(cfg: RuntimeConfig, token: string, name: string): Promise<{ customerId: string }> {
  return request(cfg, "/api/customers", token, {
    method: "POST",
    body: JSON.stringify({ name }),
  });
}

export async function deleteCustomer(cfg: RuntimeConfig, token: string, id: string): Promise<void> {
  await request(cfg, `/api/customers/${encodeURIComponent(id)}?confirm=true`, token, { method: "DELETE" });
}

export async function listTenants(cfg: RuntimeConfig, token: string, customerId: string): Promise<Tenant[]> {
  return request<Tenant[]>(cfg, `/api/customers/${encodeURIComponent(customerId)}/tenants`, token, {
    method: "GET",
  });
}

export async function createTenant(
  cfg: RuntimeConfig,
  token: string,
  customerId: string,
  name: string
): Promise<{ tenantId: string }> {
  return request(cfg, `/api/customers/${encodeURIComponent(customerId)}/tenants`, token, {
    method: "POST",
    body: JSON.stringify({ name }),
  });
}

export async function deleteTenant(
  cfg: RuntimeConfig,
  token: string,
  customerId: string,
  tenantId: string
): Promise<void> {
  await request(cfg, `/api/customers/${encodeURIComponent(customerId)}/tenants/${encodeURIComponent(tenantId)}?confirm=true`, token, {
    method: "DELETE",
  });
}

export async function seed(
  cfg: RuntimeConfig,
  token: string,
  customers: number,
  tenants: number
): Promise<{ customers: number; tenants: number }> {
  return request(cfg, "/api/seed", token, {
    method: "POST",
    body: JSON.stringify({ customers, tenants }),
  });
}

export function dbName(c: Customer): string {
  return c.db_name || c.dbName || `cust_${c.id}`;
}

export function schemaName(t: Tenant): string {
  return t.schema_name || t.schemaName || `tenant_${t.id}`;
}

export function sanitizeService(s: string): string {
  return s.toLowerCase().replace(/[^a-z0-9_]/g, "");
}

export function roleNames(tenantId: string, services: string[]): string[] {
  return services.map((s) => `app_${tenantId}_${sanitizeService(s)}`);
}

export async function listItems(
  cfg: RuntimeConfig,
  service: string,
  customerId: string,
  tenantId: string
): Promise<BackendItem[]> {
  return backendRequest<BackendItem[]>(cfg, `/${encodeURIComponent(service)}/api/v1/items`, {
    method: "GET",
    headers: { "X-Customer-Id": customerId, "X-Tenant-Id": tenantId },
  });
}

export async function createItem(
  cfg: RuntimeConfig,
  service: string,
  customerId: string,
  tenantId: string,
  name: string
): Promise<{ id?: string }> {
  return backendRequest(cfg, `/${encodeURIComponent(service)}/api/v1/items`, {
    method: "POST",
    headers: { "X-Customer-Id": customerId, "X-Tenant-Id": tenantId },
    body: JSON.stringify({ name }),
  });
}

export async function updateItem(
  cfg: RuntimeConfig,
  service: string,
  customerId: string,
  tenantId: string,
  id: string,
  name: string
): Promise<void> {
  await backendRequest(cfg, `/${encodeURIComponent(service)}/api/v1/items/${encodeURIComponent(id)}`, {
    method: "PUT",
    headers: { "X-Customer-Id": customerId, "X-Tenant-Id": tenantId },
    body: JSON.stringify({ name }),
  });
}

export async function deleteItem(
  cfg: RuntimeConfig,
  service: string,
  customerId: string,
  tenantId: string,
  id: string
): Promise<void> {
  await backendRequest(cfg, `/${encodeURIComponent(service)}/api/v1/items/${encodeURIComponent(id)}`, {
    method: "DELETE",
    headers: { "X-Customer-Id": customerId, "X-Tenant-Id": tenantId },
  });
}