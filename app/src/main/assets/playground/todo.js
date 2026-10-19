// A tiny sample project file - ask Ghost Chat to extend it.

const tasks = [];

function addTask(title) {
  tasks.push({ title, done: false });
}

function listTasks() {
  return tasks.map((t, i) => `${i + 1}. [${t.done ? "x" : " "}] ${t.title}`);
}

addTask("Try GhostIDE autocomplete");
addTask("Ask the chat to add a removeTask function");
console.log(listTasks().join("\n"));
