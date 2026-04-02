import * as os from 'os';
import * as path from 'path';

const STATE_FILE = path.join(os.homedir(), '.one-ide', 'cluster', 'state.json');

export function printHelp(): void {
  console.log(`
one-ide-cli — access live IDE editor state from the terminal

State file: ${STATE_FILE}

Commands:
  context <project-path>              Full editor context (active project, file, selection, open files, file text)
  active-project              Print the workspace root of the active project
  active-file <project-path>  Print the active file in the given project
  opened-files <project-path> Print all open files in the given project (JSON array)
  ide                         Print the active IDE name (e.g. VSCode, IntelliJ IDEA)

Options:
  --help, -h                  Show this help
`.trim());
}
