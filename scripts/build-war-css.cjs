const path = require("node:path");
const { spawnSync } = require("node:child_process");

const outputDirectory = process.env.STAREXEC_WAR_CSS_DIR;
if (!outputDirectory) {
  throw new Error("STAREXEC_WAR_CSS_DIR is required");
}

const sourceDirectory = "starexec-app/src/main/webapp/css";
const sassCli = path.join(path.dirname(require.resolve("sass")), "sass.js");
const result = spawnSync(process.execPath, [
  sassCli,
  "--style=compressed",
  "--no-source-map",
  `--load-path=${sourceDirectory}`,
  `${sourceDirectory}:${outputDirectory}`,
], { stdio: "inherit" });
if (result.error) {
  throw result.error;
}
process.exitCode = result.status ?? 1;
