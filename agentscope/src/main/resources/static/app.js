/**
 * AgentScope 前端客户端逻辑
 *
 * 功能：
 * 1. Token 验证（从 URL ?token= 或输入框获取，存 cookie）
 * 2. 左侧对话列表（localStorage 持久化，支持新建/切换/删除）
 * 3. 右侧聊天区域（支持 HTTP /api/agent/send 和 WebSocket 流式）
 * 4. 流式渲染（WS delta 累积 → 实时显示回复）
 *
 * 参考 DeepSeek 聊天界面布局
 */

// ===== 全局状态 =====
let token = '';           // 访问令牌
let conversations = [];   // 对话列表 [{id, title, messages:[]}]
let currentConvId = null;  // 当前激活对话 ID
let ws = null;             // WebSocket 连接

// ===== 工具函数 =====

/** 从 URL 查询参数提取 token */
function getTokenFromUrl() {
    const params = new URLSearchParams(location.search);
    return params.get('token') || '';
}

/** 从 localStorage 加载对话列表 */
function loadConversations() {
    const data = localStorage.getItem('agentscope_conversations');
    conversations = data ? JSON.parse(data) : [];
}

/** 保存对话列表到 localStorage */
function saveConversations() {
    localStorage.setItem('agentscope_conversations', JSON.stringify(conversations));
}

// ===== Token 验证入口 =====

/** 页面加载时检查 token：URL 有 token 直接进入，否则显示验证页 */
window.addEventListener('DOMContentLoaded', () => {
    loadConversations();
    const urlToken = getTokenFromUrl();
    if (urlToken) {
        token = urlToken;
        enterApp();
    }
    // 没有 URL token 时，显示验证页（默认 #gate 可见）
    const input = document.getElementById('tokenInput');
    input.focus();
    input.addEventListener('keydown', e => { if (e.key === 'Enter') login(); });
});

/** 点击"进入"按钮：用输入的 token 换 cookie，成功则进入主界面 */
function login() {
    token = document.getElementById('tokenInput').value.trim();
    if (!token) { alert('请输入 Token'); return; }
    // 用 token 换 cookie（同源请求会自动带 cookie）
    fetch(`/?token=${encodeURIComponent(token)}`, { redirect: 'manual' })
        .then(() => {
            // 健康检查验证
            return fetch('/api/agent/health');
        })
        .then(r => {
            if (r.ok) enterApp();
            else alert('Token 无效');
        })
        .catch(() => alert('连接失败，请检查服务是否启动'));
}

/** 进入主界面：隐藏验证页，显示聊天 UI，加载对话列表 */
function enterApp() {
    document.getElementById('gate').style.display = 'none';
    document.getElementById('app').style.display = 'flex';
    renderConvList();
    if (conversations.length === 0) newConv();
    else selectConv(conversations[0].id);
}

// ===== 对话管理 =====

/** 新建对话 */
function newConv() {
    const conv = {
        id: 'conv-' + Date.now() + '-' + Math.random().toString(36).slice(2, 8),
        title: '新对话',
        messages: []
    };
    conversations.unshift(conv);
    saveConversations();
    renderConvList();
    selectConv(conv.id);
}

/** 选择对话 */
function selectConv(id) {
    currentConvId = id;
    renderConvList();
    renderChat();
}

/** 删除对话 */
function deleteConv(id) {
    conversations = conversations.filter(c => c.id !== id);
    saveConversations();
    if (currentConvId === id) {
        currentConvId = conversations.length > 0 ? conversations[0].id : null;
    }
    renderConvList();
    renderChat();
}

/** 渲染左侧对话列表 */
function renderConvList() {
    const list = document.getElementById('convList');
    list.innerHTML = '';
    conversations.forEach(c => {
        const item = document.createElement('div');
        item.className = 'conv-item' + (c.id === currentConvId ? ' active' : '');
        item.onclick = () => selectConv(c.id);
        const title = document.createElement('span');
        title.textContent = c.title;
        item.appendChild(title);
        // 删除按钮
        const del = document.createElement('span');
        del.textContent = ' ✕';
        del.style.cssText = 'float:right;color:#ccc;cursor:pointer';
        del.onclick = (e) => { e.stopPropagation(); deleteConv(c.id); };
        item.appendChild(del);
        list.appendChild(item);
    });
}

/** 渲染右侧聊天区域 */
function renderChat() {
    const area = document.getElementById('chatArea');
    area.innerHTML = '';
    const conv = conversations.find(c => c.id === currentConvId);
    if (!conv) return;
    conv.messages.forEach(m => addMessageDom(m.role, m.content, m.tool));
}

/** 在聊天区域添加一条消息 DOM */
function addMessageDom(role, content, tool) {
    const area = document.getElementById('chatArea');
    const div = document.createElement('div');
    div.className = 'msg ' + role;
    const bubble = document.createElement('div');
    bubble.className = 'bubble';
    bubble.textContent = content;
    div.appendChild(bubble);
    if (tool) {
        const t = document.createElement('div');
        t.className = 'tool';
        t.textContent = '[工具] ' + tool;
        div.appendChild(t);
    }
    area.appendChild(div);
    area.scrollTop = area.scrollHeight;
    return bubble; // 返回 bubble 元素，流式时追加内容
}

// ===== 发送消息 =====

/** 发送消息：优先用 WebSocket 流式，否则用 HTTP 一次性 */
function send() {
    const input = document.getElementById('msgInput');
    const msg = input.value.trim();
    if (!msg) return;
    input.value = '';

    const conv = conversations.find(c => c.id === currentConvId);
    if (!conv) return;

    // 更新对话标题（首次发送时用消息内容做标题）
    if (conv.title === '新对话') {
        conv.title = msg.slice(0, 20);
        renderConvList();
    }

    // 渲染用户消息
    conv.messages.push({ role: 'user', content: msg });
    addMessageDom('user', msg);
    saveConversations();

    // 禁用发送按钮
    document.getElementById('sendBtn').disabled = true;

    // 尝试 WebSocket 流式
    if (ws && ws.readyState === WebSocket.OPEN) {
        sendViaWs(conv, msg);
    } else {
        sendViaHttp(conv, msg);
    }
}

/** 通过 HTTP /api/agent/send 发送（阻塞，等完整回复） */
function sendViaHttp(conv, msg) {
    fetch('/api/agent/send', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sessionId: conv.id, message: msg, userId: 'web-user' })
    })
    .then(r => r.json())
    .then(data => {
        const reply = data.reply || '';
        conv.messages.push({ role: 'assistant', content: reply });
        addMessageDom('assistant', reply);
        saveConversations();
    })
    .catch(e => addMessageDom('assistant', '错误: ' + e.message))
    .finally(() => { document.getElementById('sendBtn').disabled = false; });
}

/** 通过 WebSocket 流式发送（实时 delta） */
function sendViaWs(conv, msg) {
    ws.send(JSON.stringify({ action: 'prompt', sessionId: conv.id, message: msg, userId: 'web-user' }));
    // 创建一条空的 assistant bubble，流式追加
    const bubble = addMessageDom('assistant', '');
    let fullReply = '';
    let currentTool = null;

    // onmessage 在 initWs 里注册，这里通过事件监听
    ws._msgHandler = (event) => {
        const f = JSON.parse(event.data);
        if (f.sessionId !== conv.id) return;
        switch (f.event) {
            case 'delta':
                fullReply += f.data;
                bubble.textContent = fullReply;
                document.getElementById('chatArea').scrollTop = 1e9;
                break;
            case 'tool_call':
                currentTool = f.data;
                break;
            case 'done':
                conv.messages.push({ role: 'assistant', content: fullReply, tool: currentTool });
                saveConversations();
                document.getElementById('sendBtn').disabled = false;
                ws.removeEventListener('message', ws._msgHandler);
                break;
            case 'error':
                bubble.textContent = '错误: ' + f.data;
                document.getElementById('sendBtn').disabled = false;
                ws.removeEventListener('message', ws._msgHandler);
                break;
            case 'cancelled':
                bubble.textContent = fullReply + ' [已取消]';
                document.getElementById('sendBtn').disabled = false;
                ws.removeEventListener('message', ws._msgHandler);
                break;
        }
    };
    ws.addEventListener('message', ws._msgHandler);
}

// ===== WebSocket 初始化（WS 模式下自动连接） =====

/** 初始化 WebSocket 连接 */
function initWs() {
    const wsUrl = `ws://${location.hostname}:${parseInt(location.port) + 1}/ws/agent`;
    ws = new WebSocket(wsUrl);
    ws.onopen = () => console.log('[ws] connected');
    ws.onclose = () => { ws = null; console.log('[ws] closed'); };
    ws.onerror = () => { ws = null; console.log('[ws] error, 回退 HTTP'); };
}

// 页面加载后尝试连 WS（如果 ws 模式启动了则成功，否则回退 HTTP）
window.addEventListener('load', () => {
    setTimeout(() => {
        try { initWs(); } catch (e) { /* ws 未启动，回退 HTTP */ }
    }, 1000);
});
