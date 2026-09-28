"use strict";

const status = document.querySelector("#status");
const romInput = document.querySelector("#rom");
const canvas = document.querySelector("#screen");
const context = canvas.getContext("2d", { alpha: false });
const picture = context.createImageData(256, 240);
const pauseButton = document.querySelector("#pause");
const resetButton = document.querySelector("#reset");
const soundToggle = document.querySelector("#sound");
const gameName = document.querySelector("#game-name");
const emptyState = document.querySelector("#empty-state");
const dropZone = document.querySelector("#drop-zone");
const dropPrompt = document.querySelector("#drop-prompt");
const keys = new Set();
const keyBits = new Map([
  ["KeyX", 1], ["KeyZ", 2], ["ShiftLeft", 4], ["ShiftRight", 4],
  ["Enter", 8], ["ArrowUp", 16], ["ArrowDown", 32],
  ["ArrowLeft", 64], ["ArrowRight", 128]
]);

let core;
let running = false;
let frameMs = 1000 / 60.0988;
let lastTime = 0;
let accumulated = 0;
let audioContext;
let nextAudioTime = 0;

function setStatus(message, state) {
  status.textContent = message;
  status.dataset.state = state;
}

function buttons() {
  let result = 0;
  for (const key of keys) result |= keyBits.get(key) || 0;

  const pad = navigator.getGamepads?.().find(p => p && p.mapping === "standard");
  if (pad) {
    const pressed = n => pad.buttons[n]?.pressed;
    if (pressed(0)) result |= 1;
    if (pressed(1)) result |= 2;
    if (pressed(8)) result |= 4;
    if (pressed(9)) result |= 8;
    if (pressed(12) || pad.axes[1] < -0.5) result |= 16;
    if (pressed(13) || pad.axes[1] > 0.5) result |= 32;
    if (pressed(14) || pad.axes[0] < -0.5) result |= 64;
    if (pressed(15) || pad.axes[0] > 0.5) result |= 128;
  }
  return result;
}

function draw(colours) {
  const rgba = picture.data;
  for (let i = 0, j = 0; i < colours.length; i++, j += 4) {
    const colour = colours[i];
    rgba[j] = (colour >>> 16) & 255;
    rgba[j + 1] = (colour >>> 8) & 255;
    rgba[j + 2] = colour & 255;
    rgba[j + 3] = 255;
  }
  context.putImageData(picture, 0, 0);
}

function play(samples) {
  if (!soundToggle.checked || !audioContext || !samples.length) return;
  if (audioContext.state !== "running") return;
  const buffer = audioContext.createBuffer(1, samples.length, 44100);
  const output = buffer.getChannelData(0);
  for (let i = 0; i < samples.length; i++) output[i] = samples[i] / 32768;
  const source = audioContext.createBufferSource();
  source.buffer = buffer;
  source.connect(audioContext.destination);
  // Skip old queued sound when a slow frame has put the browser behind.
  nextAudioTime = Math.max(nextAudioTime, audioContext.currentTime);
  if (nextAudioTime - audioContext.currentTime > 0.15) nextAudioTime = audioContext.currentTime;
  source.start(nextAudioTime);
  nextAudioTime += buffer.duration;
}

function loop(now) {
  requestAnimationFrame(loop);
  if (!running) { lastTime = now; return; }
  accumulated = Math.min(accumulated + (now - lastTime), frameMs * 2);
  lastTime = now;

  try {
    while (accumulated >= frameMs) {
      draw(core.frame(buttons()));
      play(core.audio());
      accumulated -= frameMs;
    }
  } catch (error) {
    running = false;
    pauseButton.textContent = "Resume";
    setStatus(`Emulator error: ${error.message || error}`, "error");
    console.error(error);
  }
}

async function openRom(file) {
  if (!file || !core) return;
  const wasRunning = running;
  running = false;
  setStatus(`Loading ${file.name}…`, "loading");
  try {
    const bytes = new Uint8Array(await file.arrayBuffer());
    const digest = await crypto.subtle.digest("SHA-256", bytes);
    const sha256 = Array.from(new Uint8Array(digest), b => b.toString(16).padStart(2, "0")).join("");
    const region = core.load(bytes, file.name, sha256);
    frameMs = region === "PAL" ? 1000 / 50.007 : 1000 / 60.0988;
    if (!audioContext && window.AudioContext) audioContext = new AudioContext();
    await audioContext?.resume();
    nextAudioTime = audioContext?.currentTime || 0;
    gameName.textContent = `${file.name} · ${region}`;
    emptyState.hidden = true;
    setStatus("Running", "running");
    running = true;
    accumulated = 0;
    lastTime = performance.now();
    pauseButton.disabled = resetButton.disabled = false;
    pauseButton.textContent = "Pause";
  } catch (error) {
    running = wasRunning;
    setStatus(`Could not load ROM: ${error.message || error}`, "error");
    console.error(error);
  } finally {
    romInput.value = "";
  }
}

romInput.addEventListener("change", () => openRom(romInput.files[0]));
pauseButton.addEventListener("click", () => {
  running = !running;
  accumulated = 0;
  nextAudioTime = audioContext?.currentTime || 0;
  pauseButton.textContent = running ? "Pause" : "Resume";
  setStatus(running ? "Running" : "Paused", running ? "running" : "paused");
});
resetButton.addEventListener("click", () => {
  core.reset();
  accumulated = 0;
  nextAudioTime = audioContext?.currentTime || 0;
});
window.addEventListener("keydown", event => {
  if (!keyBits.has(event.code)) return;
  if (event.target instanceof HTMLElement && event.target.closest("button, input, label")) return;
  event.preventDefault();
  keys.add(event.code);
});
window.addEventListener("keyup", event => keys.delete(event.code));
window.addEventListener("blur", () => keys.clear());

function hasFiles(event) {
  return event.dataTransfer && Array.from(event.dataTransfer.types).includes("Files");
}

window.addEventListener("dragover", event => {
  if (!hasFiles(event)) return;
  event.preventDefault();
  event.dataTransfer.dropEffect = "copy";
  if (core) {
    dropZone.classList.add("dragging");
    dropPrompt.hidden = false;
  }
});
window.addEventListener("dragleave", event => {
  if (event.relatedTarget) return;
  dropZone.classList.remove("dragging");
  dropPrompt.hidden = true;
});
window.addEventListener("drop", event => {
  if (!hasFiles(event)) return;
  event.preventDefault();
  dropZone.classList.remove("dragging");
  dropPrompt.hidden = true;
  openRom(event.dataTransfer.files[0]);
});

async function start() {
  try {
    const teavm = await TeaVM.wasmGC.load("teavm/classes.wasm");
    core = teavm.exports;
    core.main([]);
    romInput.disabled = false;
    setStatus("Ready for a ROM", "ready");
    requestAnimationFrame(loop);
  } catch (error) {
    setStatus(`WebAssembly could not start: ${error.message || error}`, "error");
    console.error(error);
  }
}
start();
