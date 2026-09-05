/** JSON.parse plus duplicate-object-key rejection at every nesting level. */
class JsonGuard {
  private index = 0;
  private depth = 0;

  constructor(private readonly text: string) {}

  check(): void {
    this.ws();
    this.value();
    this.ws();
    if (this.index !== this.text.length) throw new Error("trailing JSON data");
  }

  private ws(): void {
    while (this.index < this.text.length && /[\u0009\u000a\u000d\u0020]/.test(this.text[this.index]!)) this.index += 1;
  }

  private value(): void {
    if (++this.depth > 32) throw new Error("JSON nesting limit");
    this.ws();
    const char = this.text[this.index];
    if (char === "{") this.object();
    else if (char === "[") this.array();
    else if (char === '"') void this.string();
    else if (char === "t") this.literal("true");
    else if (char === "f") this.literal("false");
    else if (char === "n") this.literal("null");
    else this.number();
    this.depth--;
  }

  private object(): void {
    this.index += 1;
    this.ws();
    if (this.text[this.index] === "}") { this.index += 1; return; }
    const keys = new Set<string>();
    while (true) {
      this.ws();
      if (this.text[this.index] !== '"') throw new Error("object key must be a string");
      const key = this.string();
      if (keys.has(key)) throw new Error(`duplicate JSON key: ${key}`);
      keys.add(key);
      this.ws();
      if (this.text[this.index] !== ":") throw new Error("missing JSON colon");
      this.index += 1;
      this.value();
      this.ws();
      const delimiter = this.text[this.index++];
      if (delimiter === "}") return;
      if (delimiter !== ",") throw new Error("invalid JSON object delimiter");
    }
  }

  private array(): void {
    this.index += 1;
    this.ws();
    if (this.text[this.index] === "]") { this.index += 1; return; }
    while (true) {
      this.value();
      this.ws();
      const delimiter = this.text[this.index++];
      if (delimiter === "]") return;
      if (delimiter !== ",") throw new Error("invalid JSON array delimiter");
    }
  }

  private string(): string {
    if (this.text[this.index++] !== '"') throw new Error("invalid JSON string");
    let value = "";
    while (this.index < this.text.length) {
      const char = this.text[this.index++]!;
      if (char === '"') return value;
      if (char.charCodeAt(0) < 0x20) throw new Error("unescaped JSON control character");
      if (char !== "\\") { value += char; continue; }
      const escape = this.text[this.index++];
      if (escape === undefined) throw new Error("unterminated JSON escape");
      const simple: Record<string, string> = {
        '"': '"', "\\": "\\", "/": "/", b: "\b", f: "\f", n: "\n", r: "\r", t: "\t",
      };
      if (Object.hasOwn(simple, escape)) {
        value += simple[escape];
      } else if (escape === "u") {
        const digits = this.text.slice(this.index, this.index + 4);
        if (!/^[0-9a-fA-F]{4}$/.test(digits)) throw new Error("invalid JSON unicode escape");
        value += String.fromCharCode(Number.parseInt(digits, 16));
        this.index += 4;
      } else {
        throw new Error("invalid JSON escape");
      }
    }
    throw new Error("unterminated JSON string");
  }

  private literal(expected: string): void {
    if (this.text.slice(this.index, this.index + expected.length) !== expected) throw new Error("invalid JSON literal");
    this.index += expected.length;
  }

  private number(): void {
    const start = this.index;
    if (this.text[this.index] === "-") this.index += 1;
    if (this.text[this.index] === "0") {
      this.index += 1;
      if (/[0-9]/.test(this.text[this.index] ?? "")) throw new Error("invalid leading zero");
    } else {
      if (!/[1-9]/.test(this.text[this.index] ?? "")) throw new Error("invalid JSON value");
      while (/[0-9]/.test(this.text[this.index] ?? "")) this.index += 1;
    }
    if (this.text[this.index] === ".") {
      this.index += 1;
      if (!/[0-9]/.test(this.text[this.index] ?? "")) throw new Error("invalid JSON fraction");
      while (/[0-9]/.test(this.text[this.index] ?? "")) this.index += 1;
    }
    if (this.text[this.index] === "e" || this.text[this.index] === "E") {
      this.index += 1;
      if (this.text[this.index] === "+" || this.text[this.index] === "-") this.index += 1;
      if (!/[0-9]/.test(this.text[this.index] ?? "")) throw new Error("invalid JSON exponent");
      while (/[0-9]/.test(this.text[this.index] ?? "")) this.index += 1;
    }
    if (this.index === start) throw new Error("invalid JSON number");
  }
}

export function parseStrictJson(text: string): unknown {
  new JsonGuard(text).check();
  return JSON.parse(text) as unknown;
}
