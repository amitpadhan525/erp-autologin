"""
GIET ERP Auto-Login — AI CAPTCHA Solver Server
================================================
Run: python server.py
Listens on: http://0.0.0.0:8000

Endpoints:
  GET  /           — status check
  GET  /health     — health probe (used by Android "Test Connection" button)
  POST /api/solve-captcha  — accepts Base64 image, returns solved CAPTCHA text
"""

import uvicorn
from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
import ddddocr
import base64
import logging

# ─── Logging ──────────────────────────────────────────────────────────────────
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s  %(levelname)-8s  %(name)s  %(message)s",
    datefmt="%H:%M:%S",
)
logger = logging.getLogger("CAPTCHA_Server")

# ─── App & CORS ───────────────────────────────────────────────────────────────
app = FastAPI(
    title="GIET ERP AI CAPTCHA Solver",
    description="Solves GIET BBS R ERP CAPTCHAs using ddddocr neural model",
    version="2.0.0",
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["GET", "POST"],
    allow_headers=["*"],
)

# ─── Load OCR model once at startup ──────────────────────────────────────────
logger.info("Loading ddddocr model...")
ocr = ddddocr.DdddOcr(show_ad=False)
logger.info("ddddocr model loaded — server ready.")

# ─── Request schema ───────────────────────────────────────────────────────────
class CaptchaRequest(BaseModel):
    image: str  # Base64-encoded PNG/JPEG (data URI prefix is optional)

# ─── Endpoints ────────────────────────────────────────────────────────────────

@app.get("/")
def index():
    return {
        "status": "running",
        "service": "GIET ERP AI CAPTCHA Solver",
        "version": "2.0.0",
    }


@app.get("/health")
def health():
    """
    Health probe used by the Android app's "Test Connection" button.
    Returns 200 + JSON when the server is up and the model is loaded.
    """
    return {"status": "ok", "model": "ddddocr", "ready": True}


@app.post("/api/solve-captcha")
def solve_captcha(req: CaptchaRequest):
    """
    Solve a CAPTCHA image.

    Request body:
        { "image": "<base64 string>" }

    Response:
        { "status": "success", "code": "AB3X7" }
    """
    # ── Input validation ──────────────────────────────────────────────────────
    if not req.image or len(req.image.strip()) < 20:
        raise HTTPException(
            status_code=400,
            detail="Empty or too-short base64 image string.",
        )

    try:
        raw_b64 = req.image.strip()

        # Strip data URI prefix if present (e.g. "data:image/png;base64,...")
        if "," in raw_b64:
            raw_b64 = raw_b64.split(",", 1)[1]

        img_bytes = base64.b64decode(raw_b64)

        if len(img_bytes) < 50:
            raise HTTPException(
                status_code=400,
                detail="Decoded image is too small — image data may be corrupted.",
            )

        # ── Run ddddocr ───────────────────────────────────────────────────────
        solved_code = ocr.classification(img_bytes).upper().strip()
        logger.info(f"Solved CAPTCHA → '{solved_code}'  ({len(img_bytes)} bytes input)")

        return {"status": "success", "code": solved_code}

    except HTTPException:
        raise  # re-raise our own validation errors unchanged
    except Exception as e:
        logger.error(f"OCR error: {e}")
        raise HTTPException(status_code=500, detail=f"OCR error: {str(e)}")


# ─── Entry point ──────────────────────────────────────────────────────────────
if __name__ == "__main__":
    logger.info("Starting GIET ERP AI CAPTCHA Solver on http://0.0.0.0:8000")
    logger.info("Android device must be on the same Wi-Fi / USB network.")
    uvicorn.run(app, host="0.0.0.0", port=8000, reload=False)
