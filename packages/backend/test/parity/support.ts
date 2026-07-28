import { readFileSync } from "node:fs";
import { join } from "node:path";

/** Root of the fabric mod package, resolved relative to this test file. */
export const FABRIC_MAIN_JAVA = join(import.meta.dir, "../../../fabric/src/main/java/link/sharedworld");

export function readJavaSource(relativePath: string): string {
  return readFileSync(join(FABRIC_MAIN_JAVA, relativePath), "utf8");
}
