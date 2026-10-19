import ts from "typescript";
import { readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { createHash } from "node:crypto";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL("../", import.meta.url));
const source = readFileSync(root + "upstream/freelivesports.ts", "utf8");
// Only this audited, self-contained leaf is compiled. Server adapters and their DB/browser hooks
// are deliberately not stubbed into pretending they succeeded.
const ast = ts.createSourceFile("provider.ts", source, ts.ScriptTarget.Latest, true);
for (const statement of ast.statements) {
    if (ts.isImportDeclaration(statement) && statement.moduleSpecifier.text !== "node:crypto") {
        throw new Error("New dependency requires a compatibility review");
    }
}
const compiled = ts.transpileModule(source, {
    compilerOptions: {
        target: ts.ScriptTarget.ES5,
        module: ts.ModuleKind.CommonJS,
        downlevelIteration: true,
        removeComments: true,
    },
    reportDiagnostics: true,
});
if (compiled.diagnostics?.some((d) => d.category === ts.DiagnosticCategory.Error)) {
    throw new Error("Provider compilation failed");
}
const prelude = `var exports = {};
var process = {env: {}};
function require(id) {
  if (id !== 'node:crypto') throw new Error('Unsupported module');
  return {randomUUID: function() {return uuid();}};
}
`;
const wrapper = `
function invoke(operation, payload) {
  if (operation === 'catalogue') return exports.flattenFlsRows(JSON.parse(payload));
  if (operation === 'metadata') return {url: exports.CATALOG_URL, headers: exports.FLS_HEADERS};
  if (operation === 'resolve') return {
    url: exports.fillStreamMacros(payload),
    userAgent: exports.FLS_HEADERS['User-Agent'], referer: exports.FLS_HEADERS.Referer,
    headers: exports.FLS_HEADERS
  };
  throw new Error('Unsupported operation');
}
`;
const pkg = {
    runtime: 1,
    version: Number(process.env.PROVIDER_VERSION || "1"),
    upstream: process.env.UPSTREAM_SHA || "3b860294ecb4a9ebca8819adfc061162fbbb7f99",
    modules: { freelivesports: prelude + compiled.outputText + wrapper },
    sourceSha256: createHash("sha256").update(source).digest("hex"),
};
if (!Number.isSafeInteger(pkg.version) || pkg.version < 1) throw new Error("Invalid version");
const bytes = Buffer.from(JSON.stringify(pkg));
mkdirSync(root + "dist", { recursive: true });
writeFileSync(root + "dist/providers.json", bytes);
const assets = root + "../android-tv-launcher/app/src/main/assets/providers/";
mkdirSync(assets, { recursive: true });
writeFileSync(assets + "builtin.json", bytes);
writeFileSync(assets + "LICENSE.masqueradarr.txt", readFileSync(root + "upstream/LICENSE"));
console.log(`Built runtime ${pkg.runtime}, package ${pkg.version}, ${bytes.length} bytes`);
