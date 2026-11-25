FROM python:3.11-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends libglib2.0-0 libgl1 && rm -rf /var/lib/apt/lists/*
COPY requirements.txt .
# CPU-only inference: install compatible wheels before Ultralytics resolves its dependencies.
RUN pip install --no-cache-dir --index-url https://download.pytorch.org/whl/cpu torch==2.9.1 torchvision==0.24.1
# Constraints prevent the subsequent PyPI install from replacing CPU wheels with CUDA builds.
# Preinstall LAP for ByteTrack; the runtime runs as a non-root user.
RUN printf 'torch==2.9.1+cpu\ntorchvision==0.24.1+cpu\n' > /tmp/torch-constraints.txt \
    && pip install --no-cache-dir -c /tmp/torch-constraints.txt -r requirements.txt 'lap>=0.5.12,<0.6' \
    && pip check \
    && python -c "import torch, torchvision, cv2, lap; assert torch.version.cuda is None, 'Expected CPU-only PyTorch'; print('CPU PyTorch:', torch.__version__)"
COPY app.py .
RUN useradd -r -u 10001 presenceiq
USER presenceiq
EXPOSE 8001
CMD ["uvicorn","app:api","--host","0.0.0.0","--port","8001","--workers","1"]
