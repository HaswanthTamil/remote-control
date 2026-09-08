const form = document.getElementById("commandForm");
const input = document.getElementById("commandInput");
const terminal = document.querySelector(".terminal");

form.addEventListener("submit", (event) => {
  event.preventDefault();

  const command = input.value.trim();
  if (!command) return;

  const line = document.createElement("div");
  line.className = "terminal-line";
  line.innerHTML = `<span class="prompt">$</span><span></span>`;
  line.lastElementChild.textContent = command;
  terminal.appendChild(line);

  const pending = document.createElement("div");
  pending.className = "terminal-line muted";
  pending.textContent = "Waiting for laptop…";
  terminal.appendChild(pending);

  terminal.scrollTop = terminal.scrollHeight;
  input.value = "";
  input.focus();

  // Firebase/RTDB command submission will replace this section.
});

input.focus();
