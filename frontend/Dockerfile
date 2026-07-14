# Stage 1: build the static production bundle.
FROM node:22-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm install
COPY . .
RUN npm run build

# Stage 2: serve the static files, and proxy /api to the backend container
# so the frontend code (which always calls a relative /api/... path) needs
# zero changes between `npm run dev` (Vite's own proxy) and this image
# (nginx's proxy) -- see nginx.conf.
FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
