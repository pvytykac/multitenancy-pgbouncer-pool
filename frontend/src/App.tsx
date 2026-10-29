import { useEffect, useState } from "react";
import {
  loadConfig,
  listCustomers,
  createCustomer,
  deleteCustomer,
  listTenants,
  createTenant,
  deleteTenant,
  seed,
  dbName,
  schemaName,
  roleNames,
  type RuntimeConfig,
  type Customer,
  type Tenant,
  ApiError,
} from "./api";

export default function App() {
  const [config, setConfig] = useState<RuntimeConfig | null>(null);
  const [token, setToken] = useState<string>(() => localStorage.getItem("adminToken") || "");
  const [error, setError] = useState<string>("");
  const [customers, setCustomers] = useState<Customer[]>([]);
  const [selectedCustomer, setSelectedCustomer] = useState<string>("");
  const [tenants, setTenants] = useState<Tenant[]>([]);
  const [newCustomerName, setNewCustomerName] = useState("");
  const [newTenantName, setNewTenantName] = useState("");
  const [seedCustomers, setSeedCustomers] = useState(2);
  const [seedTenants, setSeedTenants] = useState(2);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    loadConfig().then(setConfig).catch((e) => setError(String(e)));
  }, []);

  useEffect(() => {
    localStorage.setItem("adminToken", token);
  }, [token]);

  async function refreshCustomers() {
    if (!config) return;
    setLoading(true);
    setError("");
    try {
      const c = await listCustomers(config, token);
      setCustomers(c);
      if (selectedCustomer && !c.find((x) => x.id === selectedCustomer)) {
        setSelectedCustomer("");
        setTenants([]);
      }
    } catch (e) {
      setError(errMsg(e));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    if (config) refreshCustomers();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [config, token]);

  async function refreshTenants(customerId: string) {
    if (!config || !customerId) return;
    setLoading(true);
    setError("");
    try {
      const t = await listTenants(config, token, customerId);
      setTenants(t);
    } catch (e) {
      setError(errMsg(e));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    if (selectedCustomer) refreshTenants(selectedCustomer);
    else setTenants([]);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedCustomer]);

  async function handleCreateCustomer(e: React.FormEvent) {
    e.preventDefault();
    if (!config || !newCustomerName.trim()) return;
    setLoading(true);
    setError("");
    try {
      await createCustomer(config, token, newCustomerName.trim());
      setNewCustomerName("");
      await refreshCustomers();
    } catch (e) {
      setError(errMsg(e));
    } finally {
      setLoading(false);
    }
  }

  async function handleCreateTenant(e: React.FormEvent) {
    e.preventDefault();
    if (!config || !selectedCustomer || !newTenantName.trim()) return;
    setLoading(true);
    setError("");
    try {
      await createTenant(config, token, selectedCustomer, newTenantName.trim());
      setNewTenantName("");
      await refreshTenants(selectedCustomer);
    } catch (e) {
      setError(errMsg(e));
    } finally {
      setLoading(false);
    }
  }

  async function handleSeed(e: React.FormEvent) {
    e.preventDefault();
    if (!config) return;
    setLoading(true);
    setError("");
    try {
      await seed(config, token, seedCustomers, seedTenants);
      await refreshCustomers();
      if (selectedCustomer) await refreshTenants(selectedCustomer);
    } catch (e) {
      setError(errMsg(e));
    } finally {
      setLoading(false);
    }
  }

  async function handleDeleteCustomer(id: string) {
    if (!config) return;
    if (!confirm(`Delete customer ${id}?`)) return;
    setLoading(true);
    setError("");
    try {
      await deleteCustomer(config, token, id);
      await refreshCustomers();
    } catch (e) {
      setError(errMsg(e));
    } finally {
      setLoading(false);
    }
  }

  async function handleDeleteTenant(id: string) {
    if (!config || !selectedCustomer) return;
    if (!confirm(`Delete tenant ${id}?`)) return;
    setLoading(true);
    setError("");
    try {
      await deleteTenant(config, token, selectedCustomer, id);
      await refreshTenants(selectedCustomer);
    } catch (e) {
      setError(errMsg(e));
    } finally {
      setLoading(false);
    }
  }

  function errMsg(e: unknown): string {
    if (e instanceof ApiError) return `${e.message} (HTTP ${e.status})`;
    if (e instanceof Error) return e.message;
    return String(e);
  }

  const selectedCustomerObj = customers.find((c) => c.id === selectedCustomer);

  return (
    <div style={{ fontFamily: "system-ui, sans-serif", maxWidth: 960, margin: "0 auto", padding: 24 }}>
      <h1>pg-db-pooling provisioner</h1>

      <section style={{ marginBottom: 24, padding: 12, border: "1px solid #ddd", borderRadius: 8 }}>
        <label>
          Admin token (X-Admin-Token):
          <input
            type="password"
            value={token}
            onChange={(e) => setToken(e.target.value)}
            style={{ marginLeft: 8, width: 320 }}
          />
        </label>
        <div style={{ fontSize: 12, color: "#666", marginTop: 4 }}>
          API base: {config?.apiBaseUrl || "(same origin)"} {loading ? " — loading…" : ""}
        </div>
      </section>

      {error && (
        <div style={{ background: "#fee", color: "#900", padding: 12, borderRadius: 8, marginBottom: 16 }}>
          {error}
        </div>
      )}

      <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 24 }}>
        <section>
          <h2>Customers</h2>
          <form onSubmit={handleCreateCustomer} style={{ marginBottom: 12 }}>
            <input
              placeholder="customer name"
              value={newCustomerName}
              onChange={(e) => setNewCustomerName(e.target.value)}
            />
            <button type="submit" disabled={loading} style={{ marginLeft: 8 }}>
              Create
            </button>
          </form>
          <ul style={{ listStyle: "none", padding: 0 }}>
            {customers.map((c) => (
              <li
                key={c.id}
                style={{
                  padding: 8,
                  marginBottom: 4,
                  border: "1px solid #eee",
                  borderRadius: 6,
                  background: selectedCustomer === c.id ? "#eef" : "#fff",
                  cursor: "pointer",
                }}
                onClick={() => setSelectedCustomer(c.id)}
              >
                <strong>{c.id}</strong> {c.name && c.name !== c.id ? `(${c.name})` : ""}
                <div style={{ fontSize: 12, color: "#555" }}>DB: {dbName(c)}</div>
                <button
                  onClick={(e) => {
                    e.stopPropagation();
                    handleDeleteCustomer(c.id);
                  }}
                  style={{ fontSize: 11, marginTop: 4 }}
                >
                  Delete
                </button>
              </li>
            ))}
          </ul>
          {customers.length === 0 && <div style={{ color: "#888" }}>No customers yet.</div>}
        </section>

        <section>
          <h2>Tenants {selectedCustomer ? `— ${selectedCustomer}` : ""}</h2>
          {!selectedCustomer && <div style={{ color: "#888" }}>Select a customer.</div>}
          {selectedCustomer && (
            <>
              <form onSubmit={handleCreateTenant} style={{ marginBottom: 12 }}>
                <input
                  placeholder="tenant name"
                  value={newTenantName}
                  onChange={(e) => setNewTenantName(e.target.value)}
                />
                <button type="submit" disabled={loading} style={{ marginLeft: 8 }}>
                  Create
                </button>
              </form>
              {selectedCustomerObj && (
                <div style={{ fontSize: 12, color: "#555", marginBottom: 8 }}>
                  DB: {dbName(selectedCustomerObj)}
                </div>
              )}
              <ul style={{ listStyle: "none", padding: 0 }}>
                {tenants.map((t) => (
                  <li key={t.id} style={{ padding: 8, marginBottom: 4, border: "1px solid #eee", borderRadius: 6 }}>
                    <strong>{t.id}</strong>
                    <div style={{ fontSize: 12, color: "#555" }}>Schema: {schemaName(t)}</div>
                    <div style={{ fontSize: 12, color: "#555" }}>
                      Roles: {roleNames(t.id, config?.services || []).join(", ")}
                    </div>
                    <button onClick={() => handleDeleteTenant(t.id)} style={{ fontSize: 11, marginTop: 4 }}>
                      Delete
                    </button>
                  </li>
                ))}
              </ul>
              {tenants.length === 0 && <div style={{ color: "#888" }}>No tenants yet.</div>}
            </>
          )}
        </section>
      </div>

      <section style={{ marginTop: 24, padding: 12, border: "1px solid #ddd", borderRadius: 8 }}>
        <h2>Seed (optional)</h2>
        <form onSubmit={handleSeed}>
          <label>
            customers:
            <input
              type="number"
              min={1}
              value={seedCustomers}
              onChange={(e) => setSeedCustomers(Number(e.target.value))}
              style={{ width: 60, marginLeft: 4 }}
            />
          </label>
          <label style={{ marginLeft: 12 }}>
            tenants per customer:
            <input
              type="number"
              min={1}
              value={seedTenants}
              onChange={(e) => setSeedTenants(Number(e.target.value))}
              style={{ width: 60, marginLeft: 4 }}
            />
          </label>
          <button type="submit" disabled={loading} style={{ marginLeft: 12 }}>
            Seed
          </button>
        </form>
      </section>
    </div>
  );
}