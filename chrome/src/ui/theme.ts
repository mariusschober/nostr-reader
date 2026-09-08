import "./theme.css";
type Theme = "system" | "light" | "dark";
const theme = (value: unknown): Theme => value === "light" || value === "dark" ? value : "system";
function apply(value: unknown): void {
  const mode = theme(value);
  document.documentElement.dataset.theme = mode;
  const select = document.querySelector<HTMLSelectElement>("#appTheme");
  if (select) select.value = mode;
}
void chrome.storage.local.get("readerTheme").then(state => apply(state.readerTheme));
chrome.storage.onChanged.addListener((changes, area) => {
  if (area === "local" && changes.readerTheme) apply(changes.readerTheme.newValue);
});
document.querySelector<HTMLSelectElement>("#appTheme")?.addEventListener("change", event => {
  const value = theme((event.target as HTMLSelectElement).value);
  apply(value);
  void chrome.storage.local.set({ readerTheme: value });
});
