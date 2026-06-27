// MCP server that screenshots the running ReDB site in WebKit (Safari's engine)
// so an agent can verify UI changes itself. Tools:
//   - screenshot: navigate (+ optional interactions) and return a PNG
//   - snapshot:   return the page's visible text + any console errors
//
// Requires the Vite dev server to be running (default http://localhost:5180).
// Self-test:  node mcp/server.mjs --selftest [baseUrl]
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { webkit } from "playwright";
import { z } from "zod";

const DEFAULT_BASE = process.env.UI_MCP_BASE ?? "http://localhost:5180";

let browser = null;
async function getBrowser() {
  if (!browser || !browser.isConnected()) {
    browser = await webkit.launch();
  }
  return browser;
}

const actionSchema = z.object({
  do: z.enum(["click", "goto", "fill", "wait"]).describe("action kind"),
  text: z.string().optional().describe("for click: visible text of a button/link"),
  path: z.string().optional().describe("for goto: hash path, e.g. /#/planned"),
  selector: z.string().optional().describe("for fill: CSS selector"),
  value: z.string().optional().describe("for fill: value to type"),
  ms: z.number().optional().describe("for wait: milliseconds"),
});

// Opens a page at base+path, runs interactions, returns { page, context, errors }.
async function openPage({ base, path, width, height, actions, waitFor }) {
  const b = await getBrowser();
  const context = await b.newContext({
    viewport: { width: width ?? 1366, height: height ?? 900 },
    reducedMotion: "reduce", // keep animated elements stable for clicks
  });
  const errors = [];
  const page = await context.newPage();
  page.on("console", (m) => {
    if (m.type() === "error") errors.push(m.text());
  });
  page.on("pageerror", (e) => errors.push(String(e)));

  const url = `${base ?? DEFAULT_BASE}${path ?? "/"}`;
  await page.goto(url, { waitUntil: "networkidle", timeout: 15000 });

  for (const a of actions ?? []) {
    if (a.do === "click") {
      await page.locator(`:is(button,a):has-text("${a.text}")`).first().click();
    } else if (a.do === "goto") {
      await page.goto(`${base ?? DEFAULT_BASE}${a.path}`, { waitUntil: "networkidle" });
    } else if (a.do === "fill") {
      await page.fill(a.selector, a.value ?? "");
    } else if (a.do === "wait") {
      await page.waitForTimeout(a.ms ?? 300);
    }
  }
  if (waitFor) await page.waitForSelector(waitFor, { timeout: 8000 });
  await page.waitForTimeout(350);
  return { page, context, errors, url };
}

const server = new McpServer({ name: "redb-ui-webkit", version: "0.1.0" });

server.tool(
  "screenshot",
  "Screenshot the running ReDB site in WebKit (Safari engine). Navigate to a hash route, optionally run interactions (click a button/link by text, goto another route, fill an input, wait), then capture the viewport (or full page). Returns a PNG plus any console errors.",
  {
    path: z.string().optional().describe('Hash route to open, e.g. "/#/iteration/1" or "/#/planned". Default "/".'),
    base: z.string().optional().describe("Base URL of the dev server. Default http://localhost:5180."),
    width: z.number().optional().describe("Viewport width, default 1366."),
    height: z.number().optional().describe("Viewport height, default 900."),
    fullPage: z.boolean().optional().describe("Capture the full scrollable page instead of just the viewport."),
    actions: z.array(actionSchema).optional().describe("Interactions to run before capturing."),
    waitFor: z.string().optional().describe("CSS selector to wait for before capturing."),
  },
  async (args) => {
    let ctx;
    try {
      const opened = await openPage(args);
      ctx = opened.context;
      const buf = await opened.page.screenshot({ fullPage: args.fullPage ?? false });
      const content = [
        {
          type: "image",
          data: buf.toString("base64"),
          mimeType: "image/png",
        },
      ];
      const note = `WebKit · ${opened.url}` + (opened.errors.length ? `\nconsole errors:\n- ${opened.errors.join("\n- ")}` : "\nno console errors");
      content.push({ type: "text", text: note });
      return { content };
    } catch (e) {
      return { content: [{ type: "text", text: `screenshot failed: ${e.message}\n(is the dev server running at ${args.base ?? DEFAULT_BASE}?)` }], isError: true };
    } finally {
      if (ctx) await ctx.close();
    }
  }
);

server.tool(
  "snapshot",
  "Return the visible text content of the running ReDB site in WebKit, plus any console errors — useful for asserting on copy/structure without an image. Same navigation/interaction options as screenshot.",
  {
    path: z.string().optional(),
    base: z.string().optional(),
    actions: z.array(actionSchema).optional(),
    waitFor: z.string().optional(),
  },
  async (args) => {
    let ctx;
    try {
      const opened = await openPage(args);
      ctx = opened.context;
      const text = await opened.page.evaluate(() => document.body.innerText);
      const note = `WebKit · ${opened.url}` + (opened.errors.length ? `\nconsole errors:\n- ${opened.errors.join("\n- ")}` : "\nno console errors");
      return { content: [{ type: "text", text: `${note}\n\n---\n${text}` }] };
    } catch (e) {
      return { content: [{ type: "text", text: `snapshot failed: ${e.message}\n(is the dev server running at ${args.base ?? DEFAULT_BASE}?)` }], isError: true };
    } finally {
      if (ctx) await ctx.close();
    }
  }
);

async function selftest() {
  const base = process.argv[3] ?? DEFAULT_BASE;
  const { context, errors, url } = await openPage({ base, path: "/#/iteration/1" });
  console.log("selftest opened", url, "errors:", errors.length);
  await context.close();
  await (await getBrowser()).close();
}

if (process.argv.includes("--selftest")) {
  selftest().then(() => process.exit(0)).catch((e) => { console.error(e); process.exit(1); });
} else {
  const transport = new StdioServerTransport();
  await server.connect(transport);
}
