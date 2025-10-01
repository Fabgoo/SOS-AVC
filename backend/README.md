# SOS AVC Backend

Backend para o aplicativo SOS AVC - sistema de monitoramento e alerta de emergência.

## 🚀 Deploy no Render

### 1. Preparação

1. Faça commit dos arquivos do backend no GitHub
2. Certifique-se de que o arquivo `package.json` está na raiz do diretório backend

### 2. Criar aplicação no Render

1. Acesse [render.com](https://render.com)
2. Faça login ou crie uma conta
3. Clique em "New +" → "Web Service"
4. Conecte sua conta do GitHub
5. Selecione o repositório do SOS AVC
6. Configure:
   - **Name**: `sosavc-backend`
   - **Environment**: `Node`
   - **Build Command**: `npm install`
   - **Start Command**: `npm start`
   - **Root Directory**: `backend`

### 3. Variáveis de Ambiente

Configure as seguintes variáveis de ambiente no Render:

```
NODE_ENV=production
PORT=10000
JWT_SECRET=sua-chave-secreta-jwt-muito-forte-aqui
JWT_EXPIRES_IN=24h
EMAIL_USER=seu-email@gmail.com
EMAIL_PASS=sua-senha-de-app-gmail
ALLOWED_ORIGINS=https://seu-dominio.com
SERVER_URL=https://sosavc-backend.onrender.com
```

### 4. Configurar Email (Gmail)

Para receber alertas por email:

1. Ative a autenticação de 2 fatores no Gmail
2. Gere uma "senha de app" em: https://myaccount.google.com/apppasswords
3. Use essa senha na variável `EMAIL_PASS`

### 5. URLs importantes

- **Status**: `https://sosavc-backend.onrender.com/api/status`
- **Login**: `https://sosavc-backend.onrender.com/api/auth/login`
- **Dados**: `https://sosavc-backend.onrender.com/api/receber-dados`

## 📱 Configuração do App Android

Após o deploy, atualize a URL do servidor no arquivo:
`app/src/main/res/values/strings.xml`

```xml
<string name="server_url">https://sosavc-backend.onrender.com</string>
```

## 🔧 Desenvolvimento Local

```bash
cd backend
npm install
cp env.example .env
# Edite o arquivo .env com suas configurações
npm start
```

## 📊 Monitoramento

O backend inclui:
- Rate limiting para proteção contra ataques
- Autenticação JWT
- Detecção automática de emergências
- Envio de alertas por email
- Limpeza automática de dados antigos
