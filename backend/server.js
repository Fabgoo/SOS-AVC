const express = require('express');
const cors = require('cors');
const cron = require('node-cron');
const nodemailer = require('nodemailer');
const axios = require('axios');
const jwt = require('jsonwebtoken');
const bcrypt = require('bcryptjs');
const rateLimit = require('express-rate-limit');
const helmet = require('helmet');
const { body, validationResult } = require('express-validator');
require('dotenv').config();

const app = express();
const PORT = process.env.PORT || 3000;

// Middleware de segurança
app.use(helmet());

// Configuração de CORS segura
const corsOptions = {
  origin: process.env.ALLOWED_ORIGINS ? process.env.ALLOWED_ORIGINS.split(',') : ['http://localhost:3000'],
  credentials: true,
  optionsSuccessStatus: 200
};
app.use(cors(corsOptions));

// Rate limiting
const limiter = rateLimit({
  windowMs: 15 * 60 * 1000, // 15 minutos
  max: 100, // máximo 100 requests por IP por janela
  message: 'Muitas tentativas, tente novamente em 15 minutos'
});
app.use('/api/', limiter);

// Rate limiting mais restritivo para login
const loginLimiter = rateLimit({
  windowMs: 15 * 60 * 1000, // 15 minutos
  max: 5, // máximo 5 tentativas de login por IP
  message: 'Muitas tentativas de login, tente novamente em 15 minutos'
});

app.use(express.json({ limit: '10mb' }));

// Armazenamento em memória (em produção, use um banco de dados)
const users = new Map();
const alerts = new Map();
const userTokens = new Map(); // Para controle de sessões

// Middleware de autenticação
const authenticateToken = (req, res, next) => {
  const authHeader = req.headers['authorization'];
  const token = authHeader && authHeader.split(' ')[1];

  if (!token) {
    return res.status(401).json({ success: false, message: 'Token de acesso necessário' });
  }

  jwt.verify(token, process.env.JWT_SECRET, (err, user) => {
    if (err) {
      return res.status(403).json({ success: false, message: 'Token inválido' });
    }
    req.user = user;
    next();
  });
};

// Configuração do email (Gmail)
const transporter = nodemailer.createTransporter({
  service: 'gmail',
  auth: {
    user: process.env.EMAIL_USER,
    pass: process.env.EMAIL_PASS
  }
});

// Rota de registro/login (simplificada para o app)
app.post('/api/auth/login', loginLimiter, [
  body('deviceId').isLength({ min: 10, max: 100 }).trim().escape(),
  body('userName').isLength({ min: 1, max: 50 }).trim().escape()
], (req, res) => {
  try {
    const errors = validationResult(req);
    if (!errors.isEmpty()) {
      return res.status(400).json({ 
        success: false, 
        message: 'Dados inválidos',
        errors: errors.array()
      });
    }

    const { deviceId, userName } = req.body;
    
    // Criar ou atualizar usuário
    const hashedDeviceId = bcrypt.hashSync(deviceId, 10);
    const user = {
      id: hashedDeviceId,
      deviceId: deviceId,
      userName: userName,
      createdAt: new Date(),
      lastUpdate: new Date()
    };
    
    users.set(hashedDeviceId, user);
    
    // Gerar token JWT
    const token = jwt.sign(
      { userId: hashedDeviceId, deviceId: deviceId },
      process.env.JWT_SECRET,
      { expiresIn: process.env.JWT_EXPIRES_IN || '24h' }
    );
    
    // Armazenar token para controle de sessão
    userTokens.set(hashedDeviceId, token);
    
    res.status(200).json({ 
      success: true, 
      token: token,
      message: 'Autenticação realizada com sucesso'
    });

  } catch (error) {
    console.error('Erro na autenticação:', error);
    res.status(500).json({ 
      success: false, 
      message: 'Erro interno do servidor' 
    });
  }
});

// Rota para receber dados do app (agora protegida)
app.post('/api/receber-dados', authenticateToken, [
  body('interacao').isIn(['S', 'N']),
  body('movimento').isIn(['S', 'N']),
  body('localizacao').isLength({ max: 500 }).trim(),
  body('conectado').isIn(['S', 'N']),
  body('em_horario_de_sono').isIn(['S', 'N']),
  body('contact1').optional().isLength({ max: 100 }).trim().escape(),
  body('contact2').optional().isLength({ max: 100 }).trim().escape(),
  body('contact3').optional().isLength({ max: 100 }).trim().escape()
], (req, res) => {
  try {
    const errors = validationResult(req);
    if (!errors.isEmpty()) {
      return res.status(400).json({ 
        success: false, 
        message: 'Dados inválidos',
        errors: errors.array()
      });
    }

    const {
      interacao,
      movimento,
      localizacao,
      conectado,
      em_horario_de_sono,
      contact1,
      contact2,
      contact3
    } = req.body;

    const userId = req.user.userId;
    const user = users.get(userId);
    
    if (!user) {
      return res.status(404).json({ 
        success: false, 
        message: 'Usuário não encontrado' 
      });
    }

    console.log(`Dados recebidos do usuário ${user.userName}:`, {
      interacao, movimento, localizacao, conectado, em_horario_de_sono
    });

    // Atualizar dados do usuário
    user.lastUpdate = new Date();
    user.lastInteraction = interacao === 'S';
    user.lastMovement = movimento === 'S';
    user.location = localizacao;
    user.isCharging = conectado === 'S';
    user.isSleepTime = em_horario_de_sono === 'S';
    user.contact1 = contact1 || '';
    user.contact2 = contact2 || '';
    user.contact3 = contact3 || '';

    users.set(userId, user);

    // Verificar se há sinais de emergência
    checkForEmergency(userId);

    res.status(200).json({ 
      success: true, 
      message: 'Dados recebidos com sucesso' 
    });

  } catch (error) {
    console.error('Erro ao processar dados:', error);
    res.status(500).json({ 
      success: false, 
      message: 'Erro interno do servidor' 
    });
  }
});

// Função para verificar emergências
function checkForEmergency(userId) {
  const user = users.get(userId);
  if (!user) return;

  const now = new Date();
  const timeSinceLastUpdate = now - user.lastUpdate;
  const timeSinceLastInteraction = user.lastInteraction ? 0 : timeSinceLastUpdate;
  const timeSinceLastMovement = user.lastMovement ? 0 : timeSinceLastUpdate;

  // Critérios para detectar possível emergência:
  // 1. Sem interação por mais de 2 horas (exceto durante sono)
  // 2. Sem movimento por mais de 1 hora (exceto durante sono)
  // 3. Não está carregando (pode indicar que o telefone morreu)
  
  const isEmergency = (
    (!user.isSleepTime && timeSinceLastInteraction > 2 * 60 * 60 * 1000) || // 2 horas sem interação
    (!user.isSleepTime && timeSinceLastMovement > 1 * 60 * 60 * 1000) || // 1 hora sem movimento
    (!user.isCharging && timeSinceLastUpdate > 4 * 60 * 60 * 1000) // 4 horas sem atualização
  );

  if (isEmergency) {
    console.log(`🚨 POSSÍVEL EMERGÊNCIA detectada para usuário ${user.userName}`);
    
    // Evitar múltiplos alertas para o mesmo usuário
    const lastAlert = alerts.get(userId);
    if (!lastAlert || (now - lastAlert) > 30 * 60 * 1000) { // 30 minutos entre alertas
      sendEmergencyAlert(user);
      alerts.set(userId, now);
    }
  }
}

// Função para enviar alertas de emergência
async function sendEmergencyAlert(user) {
  const contacts = [user.contact1, user.contact2, user.contact3].filter(c => c && c.trim());
  
  if (contacts.length === 0) {
    console.log(`Nenhum contato encontrado para usuário ${user.userName}`);
    return;
  }

  const message = `
🚨 ALERTA DE EMERGÊNCIA - SOS AVC

Nome: ${user.userName}
ID: ${user.deviceId}

O aplicativo SOS AVC detectou que ${user.userName} pode estar em uma situação de emergência:

• Última interação: ${user.lastInteraction ? 'Sim' : 'Não'}
• Último movimento: ${user.lastMovement ? 'Sim' : 'Não'}
• Localização: ${user.location}
• Carregando: ${user.isCharging ? 'Sim' : 'Não'}
• Horário de sono: ${user.isSleepTime ? 'Sim' : 'Não'}

POR FAVOR, entre em contato imediatamente para verificar se está tudo bem!

Este é um alerta automático do aplicativo SOS AVC.
  `.trim();

  // Enviar email para cada contato
  for (const contact of contacts) {
    try {
      await sendEmail(contact, '🚨 ALERTA DE EMERGÊNCIA - SOS AVC', message);
      console.log(`Email enviado para: ${contact}`);
    } catch (error) {
      console.error(`Erro ao enviar email para ${contact}:`, error);
    }
  }
}

// Função para enviar email
async function sendEmail(to, subject, text) {
  if (!process.env.EMAIL_USER || !process.env.EMAIL_PASS) {
    console.log('Configuração de email não encontrada. Simulando envio...');
    return;
  }

  const mailOptions = {
    from: process.env.EMAIL_USER,
    to: to,
    subject: subject,
    text: text
  };

  await transporter.sendMail(mailOptions);
}

// Rota de status do servidor (pública)
app.get('/api/status', (req, res) => {
  res.json({
    status: 'online',
    users: users.size,
    timestamp: new Date().toISOString(),
    version: '2.0.0'
  });
});

// Rota para logout
app.post('/api/auth/logout', authenticateToken, (req, res) => {
  const userId = req.user.userId;
  userTokens.delete(userId);
  res.json({ success: true, message: 'Logout realizado com sucesso' });
});

// Rota para listar usuários (apenas para administradores - removida por segurança)
// Esta rota foi removida por questões de segurança

// Middleware de tratamento de erros
app.use((err, req, res, next) => {
  console.error('Erro não tratado:', err);
  res.status(500).json({ 
    success: false, 
    message: 'Erro interno do servidor' 
  });
});

// Middleware para rotas não encontradas
app.use('*', (req, res) => {
  res.status(404).json({ 
    success: false, 
    message: 'Rota não encontrada' 
  });
});

// Iniciar servidor
app.listen(PORT, () => {
  console.log(`🚀 Servidor SOS AVC rodando na porta ${PORT}`);
  console.log(`📊 Status: http://localhost:${PORT}/api/status`);
  console.log(`🔒 Modo: ${process.env.NODE_ENV || 'development'}`);
});

// Limpar dados antigos periodicamente (a cada 24 horas)
cron.schedule('0 0 * * *', () => {
  const now = new Date();
  const oneDayAgo = now - (24 * 60 * 60 * 1000);
  
  for (const [userId, user] of users.entries()) {
    if (user.lastUpdate < oneDayAgo) {
      users.delete(userId);
      userTokens.delete(userId);
      console.log(`Usuário ${user.userName} removido por inatividade`);
    }
  }
});