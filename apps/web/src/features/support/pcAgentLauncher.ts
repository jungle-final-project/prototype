import { getPcAgentConnectionStatus } from './supportApi';

export const PC_AGENT_PROTOCOL_URL = 'buildgraph-pc-agent://open';
export const PC_AGENT_CONNECTION_POLL_INTERVAL_MS = 500;
export const PC_AGENT_CONNECTION_POLL_ATTEMPTS = 20;

export async function ensurePcAgentConnected(signal?: AbortSignal) {
  if ((await getPcAgentConnectionStatus(signal)).connected) return true;

  launchInstalledPcAgent();
  for (let attempt = 0; attempt < PC_AGENT_CONNECTION_POLL_ATTEMPTS; attempt += 1) {
    await delay(PC_AGENT_CONNECTION_POLL_INTERVAL_MS, signal);
    if ((await getPcAgentConnectionStatus(signal)).connected) return true;
  }
  return false;
}

export function launchInstalledPcAgent() {
  const link = document.createElement('a');
  link.href = PC_AGENT_PROTOCOL_URL;
  link.hidden = true;
  document.body.appendChild(link);
  link.click();
  link.remove();
}

function delay(milliseconds: number, signal?: AbortSignal) {
  return new Promise<void>((resolve, reject) => {
    if (signal?.aborted) {
      reject(signal.reason);
      return;
    }
    const onAbort = () => {
      window.clearTimeout(timer);
      reject(signal?.reason);
    };
    const timer = window.setTimeout(() => {
      signal?.removeEventListener('abort', onAbort);
      resolve();
    }, milliseconds);
    signal?.addEventListener('abort', onAbort, { once: true });
  });
}
