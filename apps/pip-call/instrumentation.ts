// Runs once when the server starts: fail fast, naming any missing environment variable.
export async function register() {
  if (process.env.NEXT_RUNTIME === "nodejs") await import("./src/startup");
}
