web: python -m flask --app run.py migrate-db && exec gunicorn --workers 2 --bind 0.0.0.0:$PORT wsgi:app
