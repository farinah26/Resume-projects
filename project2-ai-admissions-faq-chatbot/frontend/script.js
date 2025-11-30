const API_URL = "https://35ame4rzfc.execute-api.us-east-1.amazonaws.com/chat"; 

function appendMessage(sender, text) {
  const chat = document.getElementById("chat-window");
  const messageDiv = document.createElement("div");
  messageDiv.classList.add("message", sender);

  const label = document.createElement("span");
  label.classList.add("label");
  label.textContent = sender === "user" ? "You" : "Assistant";

  const bubble = document.createElement("div");
  bubble.classList.add("bubble");
  bubble.textContent = text;

  messageDiv.appendChild(label);
  messageDiv.appendChild(document.createElement("br"));
  messageDiv.appendChild(bubble);

  chat.appendChild(messageDiv);
  chat.scrollTop = chat.scrollHeight;
}

async function sendMessage() {
  const input = document.getElementById("question");
  const question = input.value.trim();
  if (!question) return;

  appendMessage("user", question);
  input.value = "";

  try {
    const response = await fetch(API_URL, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ question })
    });

    if (!response.ok) {
      appendMessage("bot", "Error: " + response.status + " " + response.statusText);
      return;
    }

    const data = await response.json();
    appendMessage("bot", data.answer || "No answer field in response.");
  } catch (err) {
    appendMessage("bot", "There was an error contacting the server: " + err.message);
  }
}
