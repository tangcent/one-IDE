import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import type { ClusterState, State } from './types.js';

const STATE_FILE = path.join(os.homedir(), '.one-ide', 'cluster', 'state.json');
const STALE_THRESHOLD_MS = 30_000;

export function readState(): State {
  if (!fs.existsSync(STATE_FILE)) {
    console.error(`No state file found at ${STATE_FILE}`);
    console.error('Make sure the One-IDE plugin is installed and an IDE is open.');
    process.exit(2);
  }

  let clusterState: ClusterState;
  try {
    clusterState = JSON.parse(fs.readFileSync(STATE_FILE, 'utf-8')) as ClusterState;
  } catch (e) {
    console.error(`Failed to read state file: ${(e as Error).message}`);
    process.exit(2);
  }

  const age = Date.now() - (clusterState.timestamp ?? 0);
  if (age > STALE_THRESHOLD_MS) {
    const ageS = Math.round(age / 1000);
    process.stderr.write(`[one-ide-cli] Warning: state is ${ageS}s old — IDE may be closed or idle.\n`);
  }

  return clusterState.state ?? ({} as State);
}
