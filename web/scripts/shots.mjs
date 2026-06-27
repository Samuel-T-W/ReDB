// Captures screenshots of the running dev server in WebKit (Safari engine) and
// Chromium. Usage: node scripts/shots.mjs [baseUrl]
import { webkit } from "playwright";
import { mkdirSync } from "node:fs";

const BASE = process.argv[2] ?? "http://localhost:5180";
const OUT = new URL("../shots/", import.meta.url).pathname;
mkdirSync(OUT, { recursive: true });

// WebKit only — Safari's rendering engine.
const engines = [{ name: "webkit", launcher: webkit }];

const wait = (ms) => new Promise((r) => setTimeout(r, ms));

for (const { name, launcher } of engines) {
  const browser = await launcher.launch();
  const page = await browser.newPage({
    viewport: { width: 1366, height: 900 },
    reducedMotion: "reduce",
  });

  await page.goto(`${BASE}/#/iteration/1`, { waitUntil: "networkidle" });
  await wait(500);
  await page.screenshot({ path: `${OUT}${name}-iteration.png` });

  await page.getByRole("button", { name: "How it works" }).click();
  await wait(500);
  await page.screenshot({ path: `${OUT}${name}-explain.png` });

  await page.getByRole("button", { name: "Performance" }).click();
  await wait(500);
  await page.screenshot({ path: `${OUT}${name}-performance.png` });

  await page.goto(`${BASE}/#/planned`, { waitUntil: "networkidle" });
  await wait(400);
  await page.screenshot({ path: `${OUT}${name}-planned.png` });

  await browser.close();
  console.log(`${name}: 4 shots written`);
}
console.log("done →", OUT);
