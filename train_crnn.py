"""
CRNN CAPTCHA Solver — Complete Training Script
================================================
Architecture:  CNN (feature extractor) → BiLSTM (sequence model) → FC → CTC Loss
Input:         100×40 grayscale CAPTCHA images
Output:        crnn_best.pth  (best checkpoint by validation accuracy)

Usage:
    python train_crnn.py

The script generates synthetic training data using PIL so no external
dataset is needed. For best results, also add real CAPTCHAs from the
portal to the captcha_test_set/ directory and label them.
"""

import os
import random
import string

import torch
import torch.nn as nn
import torch.optim as optim
from torch.utils.data import Dataset, DataLoader, random_split
from PIL import Image, ImageDraw, ImageFilter
import torchvision.transforms as transforms

# ─── Configuration ────────────────────────────────────────────────────────────
CHARSET      = string.ascii_uppercase + string.digits   # A-Z + 0-9 = 36 chars
BLANK_IDX    = len(CHARSET)                             # CTC blank token index = 36
NUM_CLASSES  = len(CHARSET) + 1                         # 37 (36 chars + 1 blank)

IMG_W, IMG_H    = 100, 40       # GIET ERP CAPTCHA dimensions
CAPTCHA_LEN     = 5             # characters per CAPTCHA
NUM_TRAIN       = 5000          # synthetic training samples
NUM_EPOCHS      = 50
BATCH_SIZE      = 32
LR              = 1e-3
CHECKPOINT_PATH = "crnn_best.pth"

# ─── Char ↔ Index helpers ─────────────────────────────────────────────────────
def char_to_idx(c: str) -> int:
    return CHARSET.index(c.upper())

def idx_to_char(i: int) -> str:
    return CHARSET[i]

# ─── Synthetic CAPTCHA Generator ─────────────────────────────────────────────

def generate_captcha_image(text: str) -> Image.Image:
    """
    Generates a single noisy grayscale CAPTCHA image similar to the
    GIET BBS R ERP portal style (white background, dark distorted text,
    random lines, slight noise).
    """
    img  = Image.new("L", (IMG_W, IMG_H), color=240)
    draw = ImageDraw.Draw(img)

    # Random background lines
    for _ in range(random.randint(3, 6)):
        x1 = random.randint(0, IMG_W)
        y1 = random.randint(0, IMG_H)
        x2 = random.randint(0, IMG_W)
        y2 = random.randint(0, IMG_H)
        draw.line([(x1, y1), (x2, y2)], fill=random.randint(140, 210), width=1)

    # Draw each character with random offset and size
    char_w = IMG_W // (CAPTCHA_LEN + 1)
    for i, ch in enumerate(text):
        x = char_w * i + random.randint(-2, 4) + 4
        y = random.randint(2, IMG_H // 4)
        draw.text((x, y), ch, fill=random.randint(0, 70))

    # Salt-and-pepper noise
    pixels = img.load()
    for _ in range(random.randint(60, 180)):
        px = random.randint(0, IMG_W - 1)
        py = random.randint(0, IMG_H - 1)
        pixels[px, py] = random.randint(0, 255)

    # Light blur to mimic anti-aliasing
    img = img.filter(ImageFilter.GaussianBlur(radius=0.4))
    return img

# ─── Dataset ─────────────────────────────────────────────────────────────────

class CaptchaDataset(Dataset):
    """
    Generates synthetic CAPTCHA samples on construction.
    Each item: (image_tensor [1, H, W], label_tensor [CAPTCHA_LEN], label_len)
    """

    def __init__(self, size: int = NUM_TRAIN):
        self.transform = transforms.Compose([
            transforms.Resize((IMG_H, IMG_W)),
            transforms.ToTensor(),                        # → [0, 1]
            transforms.Normalize(mean=[0.5], std=[0.5])   # → [-1, 1]
        ])
        print(f"  Generating {size} synthetic CAPTCHA samples...", flush=True)
        self.samples = []
        for _ in range(size):
            text = "".join(random.choices(CHARSET, k=CAPTCHA_LEN))
            img  = generate_captcha_image(text)
            self.samples.append((img, text))
        print(f"  Done — dataset ready ({size} samples).", flush=True)

    def __len__(self) -> int:
        return len(self.samples)

    def __getitem__(self, idx):
        img, text = self.samples[idx]
        tensor = self.transform(img)
        label  = torch.tensor([char_to_idx(c) for c in text], dtype=torch.long)
        return tensor, label, len(text)


def collate_fn(batch):
    """Custom collate: flattens labels into a 1-D tensor for CTC loss."""
    images, labels, lengths = zip(*batch)
    images      = torch.stack(images)
    labels_flat = torch.cat(labels)
    lengths     = torch.tensor(lengths, dtype=torch.long)
    return images, labels_flat, lengths

# ─── CRNN Model ──────────────────────────────────────────────────────────────

class CRNN(nn.Module):
    """
    Convolutional Recurrent Neural Network for text sequence recognition.

    CNN output: (B, 128, W/4, H/4)  →  flattened to (B, W/4, 128*(H/4))
    BiLSTM:     hidden=128, bidirectional → output dim = 256
    FC:         256 → NUM_CLASSES (36 chars + 1 CTC blank)
    """

    def __init__(self, num_classes: int = NUM_CLASSES):
        super().__init__()

        # Feature extractor
        self.cnn = nn.Sequential(
            nn.Conv2d(1, 32, kernel_size=3, padding=1),
            nn.BatchNorm2d(32),
            nn.ReLU(inplace=True),
            nn.MaxPool2d(2, 2),           # 50 × 20

            nn.Conv2d(32, 64, kernel_size=3, padding=1),
            nn.BatchNorm2d(64),
            nn.ReLU(inplace=True),
            nn.MaxPool2d(2, 2),           # 25 × 10

            nn.Conv2d(64, 128, kernel_size=3, padding=1),
            nn.BatchNorm2d(128),
            nn.ReLU(inplace=True),
        )

        # Sequence model
        self.rnn = nn.LSTM(
            input_size=128 * 10,   # 128 channels × (IMG_H / 4) = 128 × 10
            hidden_size=128,
            num_layers=2,
            bidirectional=True,
            batch_first=True,
            dropout=0.3,
        )

        self.fc = nn.Linear(128 * 2, num_classes)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        feats = self.cnn(x)                              # (B, 128, W, H)
        B, C, W, H = feats.size()
        feats = feats.permute(0, 2, 1, 3).contiguous()  # (B, W, C, H)
        feats = feats.view(B, W, C * H)                  # (B, W, C*H)
        rnn_out, _ = self.rnn(feats)                     # (B, W, 256)
        out = self.fc(rnn_out)                           # (B, W, num_classes)
        return out

# ─── Greedy CTC Decoder ───────────────────────────────────────────────────────

def decode_ctc(output: torch.Tensor) -> list:
    """
    Greedy best-path CTC decoder.
    Collapses consecutive identical tokens and removes blank tokens.
    """
    _, preds = output.max(2)   # (B, T)
    results = []
    for pred in preds.tolist():
        chars = []
        prev  = -1
        for idx in pred:
            if idx != BLANK_IDX and idx != prev:
                chars.append(idx_to_char(idx))
            prev = idx
        results.append("".join(chars))
    return results

# ─── Training Loop ───────────────────────────────────────────────────────────

def train():
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    print(f"\n{'='*55}")
    print(f"  CRNN CAPTCHA Solver Training")
    print(f"  Device : {device}")
    print(f"  Charset: {CHARSET}  ({len(CHARSET)} chars)")
    print(f"  Classes: {NUM_CLASSES}  (incl. CTC blank)")
    print(f"{'='*55}\n")

    # ── Dataset split ─────────────────────────────────────────────────────────
    dataset  = CaptchaDataset(NUM_TRAIN)
    n_train  = int(0.9 * len(dataset))
    n_val    = len(dataset) - n_train
    train_ds, val_ds = random_split(dataset, [n_train, n_val])

    train_loader = DataLoader(
        train_ds, batch_size=BATCH_SIZE, shuffle=True,
        collate_fn=collate_fn, num_workers=0
    )
    val_loader = DataLoader(
        val_ds, batch_size=BATCH_SIZE, shuffle=False,
        collate_fn=collate_fn, num_workers=0
    )

    # ── Model, loss, optimizer ────────────────────────────────────────────────
    model    = CRNN(NUM_CLASSES).to(device)
    ctc_loss = nn.CTCLoss(blank=BLANK_IDX, reduction="mean", zero_infinity=True)
    optimizer = optim.Adam(model.parameters(), lr=LR)
    scheduler = optim.lr_scheduler.ReduceLROnPlateau(
        optimizer, mode="min", patience=5, factor=0.5, verbose=True
    )

    best_val_acc = 0.0
    print(f"{'Epoch':>5}  {'Train Loss':>10}  {'Val Acc':>8}  {'Best':>6}")
    print("-" * 40)

    for epoch in range(1, NUM_EPOCHS + 1):

        # ── Train ─────────────────────────────────────────────────────────────
        model.train()
        total_loss = 0.0

        for images, labels, lengths in train_loader:
            images = images.to(device)
            labels = labels.to(device)

            output   = model(images)                          # (B, T, C)
            log_probs = output.permute(1, 0, 2).log_softmax(2)  # (T, B, C)
            T = log_probs.size(0)
            input_lengths = torch.full(
                (images.size(0),), T, dtype=torch.long, device=device
            )

            loss = ctc_loss(log_probs, labels, input_lengths, lengths.to(device))

            optimizer.zero_grad()
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), max_norm=5.0)
            optimizer.step()

            total_loss += loss.item()

        avg_loss = total_loss / len(train_loader)

        # ── Validate ──────────────────────────────────────────────────────────
        model.eval()
        correct = 0
        total   = 0

        with torch.no_grad():
            for images, labels, lengths in val_loader:
                images = images.to(device)
                output = model(images)
                preds  = decode_ctc(output)

                offset = 0
                for i, length in enumerate(lengths.tolist()):
                    gt = "".join(
                        idx_to_char(labels[offset + j].item())
                        for j in range(length)
                    )
                    if preds[i] == gt:
                        correct += 1
                    total  += 1
                    offset += length

        val_acc = correct / total * 100 if total > 0 else 0.0
        scheduler.step(avg_loss)

        is_best = val_acc > best_val_acc
        if is_best:
            best_val_acc = val_acc
            torch.save(model.state_dict(), CHECKPOINT_PATH)

        mark = "  ← saved" if is_best else ""
        print(f"{epoch:>5}  {avg_loss:>10.4f}  {val_acc:>7.1f}%  {best_val_acc:>5.1f}%{mark}")

    print(f"\nTraining complete!")
    print(f"Best validation accuracy : {best_val_acc:.1f}%")
    print(f"Checkpoint saved to      : {CHECKPOINT_PATH}")


# ─── Entry point ─────────────────────────────────────────────────────────────
if __name__ == "__main__":
    train()
