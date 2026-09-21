from fastapi import FastAPI
from transformers import pipeline

app = FastAPI()

classifier = pipeline(
    "sentiment-analysis",
    model="distilbert-base-uncased-finetuned-sst-2-english"
)

@app.get("/health")
def health():
    return {"status": "healthy"}

@app.post("/predict")
def predict(text: str):
    result = classifier(text)
    return {"result": result}