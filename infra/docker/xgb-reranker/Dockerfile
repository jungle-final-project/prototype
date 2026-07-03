FROM python:3.12-slim

WORKDIR /app

COPY tools/recommendation_reranker_requirements.txt /tmp/requirements.txt
RUN pip install --no-cache-dir -r /tmp/requirements.txt

COPY tools/reranker_service.py /app/tools/reranker_service.py

EXPOSE 8091

CMD ["python", "tools/reranker_service.py", "--host", "0.0.0.0", "--port", "8091"]
