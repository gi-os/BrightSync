FROM python:3.12-alpine
RUN pip install --no-cache-dir fastapi==0.115.6 uvicorn==0.34.0 httpx==0.28.1
WORKDIR /srv
COPY app.py calendar_bridge.py ./
ENV LIGHTSYNC_DIR=/data
VOLUME /data
EXPOSE 8099
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8099"]
