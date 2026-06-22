// 生成 init.sql 测试数据
const crypto = require('crypto');
const fs = require('fs');

const PWD_HASH = '$2b$10$FFazAHt3BPFM90IXFHjuM.1858i7omXn9IDf0aDxoxYAbDcr3dlOS';

// 中文名字库
const surnames = ['王','李','张','刘','陈','杨','黄','赵','吴','周','徐','孙','马','朱','胡','郭','何','高','林','罗','郑','梁','谢','宋','唐','韩','曹','许','邓','冯','萧','程','蔡','彭','潘','袁','于','董','余','苏','叶','吕','魏','蒋','田','杜','丁','沈','姜','范','江','傅','钟','卢','汪','戴','崔','任','陆','廖','姚','方','金','邱','夏','谭','韦','贾','邹','石','熊','孟','秦','阎','薛','侯','雷','白','龙','段','郝','孔','邵','史','毛','常','万','顾','赖','武','康','贺','严','尹','钱','施','牛','洪','龚'];
const names = ['伟','芳','娜','敏','静','丽','强','磊','洋','勇','艳','杰','军','秀英','华','慧','明','鑫','桂英','文','飞','志强','莉','波','斌','超','建平','涛','刚','健','秀兰','建国','玉兰','小红','鹏','婷婷','宇','帅','浩','欢','鑫鹏','浩然','欣怡','子涵','梓涵','雨桐','一诺','梦瑶','思远','晓明','雪','蕾','晨','曦','俊杰','博文','艺','诗涵','若溪','紫萱','璟雯'];

function rand(min, max) { return Math.floor(Math.random() * (max - min + 1)) + min; }
function pick(arr) { return arr[rand(0, arr.length - 1)]; }
function nickname() { return pick(surnames) + pick(names); }
function phone(i) { return '138' + String(i + 1).padStart(8, '0'); }
function dt(y,m,d,h,min) { return `'2026-${String(m).padStart(2,'0')}-${String(d).padStart(2,'0')} ${String(h).padStart(2,'0')}:${String(min).padStart(2,'0')}:00'`; }

const lines = [];

// ========== 100 用户 ==========
lines.push('-- 100 个测试用户（密码均为 123456）');
lines.push('INSERT INTO `user` (`phone`, `password`, `nickname`) VALUES');
// user_id=1 保留为测试账号
lines.push("('13800138000', '${PWD_HASH}', '测试用户'),");
for (let i = 1; i < 100; i++) {
  lines.push(`('${phone(i)}', '${PWD_HASH}', '${nickname()}')${i < 99 ? ',' : ';'}`);
}
lines.push('');

// ========== 15 场活动 ==========
const events = [
  {id:1, title:'#1 毕业晚会「青春不散场」', desc:'又是一年毕业季，让我们用一场盛大的晚会告别青春。节目涵盖歌舞、小品、乐队演出，还有神秘嘉宾登场。', org:'校学生会', venue:'学校大礼堂', saleStart:dt(0,6,20,10,0), saleEnd:dt(0,7,9,20,0), evStart:dt(0,7,10,19,30), evEnd:dt(0,7,10,22,0), status:1},
  {id:2, title:'#2 校园歌手大赛总决赛', desc:'12位校园歌手站上总决赛舞台。专业评委团现场打分，大众评审团参与投票。', org:'校团委 × 音乐协会', venue:'体育馆', saleStart:dt(0,7,1,10,0), saleEnd:dt(0,7,19,18,0), evStart:dt(0,7,20,18,0), evEnd:dt(0,7,20,21,30), status:0},
  {id:3, title:'#3 新年联欢晚会', desc:'校艺术团精心筹备的高水准文艺汇演，交响乐、合唱、民族舞、魔术等节目精彩纷呈。', org:'校艺术团', venue:'音乐厅', saleStart:dt(0,7,15,10,0), saleEnd:dt(0,7,31,18,0), evStart:dt(0,8,1,19,0), evEnd:dt(0,8,1,22,0), status:0},
  {id:4, title:'#4 校园戏剧节「仲夏夜之梦」', desc:'戏剧社年度大戏——莎士比亚经典喜剧改编版。古典与现代的碰撞。', org:'戏剧社', venue:'黑匣子剧场', saleStart:dt(0,7,10,10,0), saleEnd:dt(0,7,24,20,0), evStart:dt(0,7,25,19,30), evEnd:dt(0,7,25,21,0), status:0},
  {id:5, title:'#5 校园篮球联赛决赛', desc:'经过一个月激烈角逐，两支最强球队会师决赛。现场DJ、啦啦队表演、中场互动游戏。', org:'体育部', venue:'综合体育馆', saleStart:dt(0,6,22,9,0), saleEnd:dt(0,7,5,18,0), evStart:dt(0,7,6,15,0), evEnd:dt(0,7,6,18,0), status:1},
  {id:6, title:'#6 动漫嘉年华「次元狂欢」', desc:'Cosplay大赛、同人展、声优见面会、周边集市。二次元爱好者的年度盛宴。', org:'动漫社', venue:'学生活动中心', saleStart:dt(0,6,25,12,0), saleEnd:dt(0,7,12,20,0), evStart:dt(0,7,13,10,0), evEnd:dt(0,7,13,20,0), status:1},
  {id:7, title:'#7 校园美食节', desc:'汇集各地特色小吃，学生食堂大厨+校外知名摊位联袂献艺。现场还有厨艺大赛。', org:'后勤集团 × 学生会', venue:'操场广场', saleStart:dt(0,6,18,8,0), saleEnd:dt(0,6,28,20,0), evStart:dt(0,6,29,10,0), evEnd:dt(0,6,29,21,0), status:1},
  {id:8, title:'#8 摄影大赛作品展', desc:'「校园之美」主题摄影大赛优秀作品展，涵盖风光、人像、纪实等多个组别。', org:'摄影协会', venue:'图书馆展厅', saleStart:dt(0,7,5,9,0), saleEnd:dt(0,7,18,17,0), evStart:dt(0,7,20,9,0), evEnd:dt(0,7,30,17,0), status:0},
  {id:9, title:'#9 校园音乐节「夏日音浪」', desc:'连续 6 小时不间断演出，摇滚、民谣、说唱、电子等多种风格乐队轮番登台。', org:'音乐协会', venue:'操场主舞台', saleStart:dt(0,7,8,10,0), saleEnd:dt(0,7,28,20,0), evStart:dt(0,7,30,16,0), evEnd:dt(0,7,30,22,0), status:0},
  {id:10, title:'#10 辩论赛总决赛', desc:'正方反方围绕「AI是否将取代人类创意工作」展开巅峰对决。特邀校友企业家担任评委。', org:'辩论队', venue:'学术报告厅', saleStart:dt(0,6,22,10,0), saleEnd:dt(0,7,2,18,0), evStart:dt(0,7,3,19,0), evEnd:dt(0,7,3,21,30), status:2},
  {id:11, title:'#11 街舞大赛「舞力全开」', desc:'Breaking、Popping、Locking、Hip-hop 四大舞种PK。冠军将代表学校参加全国大学生街舞联赛。', org:'街舞社', venue:'体育馆', saleStart:dt(0,7,12,10,0), saleEnd:dt(0,8,2,18,0), evStart:dt(0,8,3,18,0), evEnd:dt(0,8,3,21,0), status:0},
  {id:12, title:'#12 校园马拉松', desc:'5公里欢乐跑+半程马拉松，环绕校园最美路线。完赛即获纪念奖牌。', org:'体育部 × 跑步协会', venue:'校园环线', saleStart:dt(0,7,1,8,0), saleEnd:dt(0,7,14,20,0), evStart:dt(0,7,16,7,0), evEnd:dt(0,7,16,11,0), status:0},
  {id:13, title:'#13 英语演讲比赛', desc:'主题「Change & Challenge」。全校各院系选拔出的12强选手带来精彩英文演讲。', org:'外国语学院', venue:'国际交流中心', saleStart:dt(0,6,25,9,0), saleEnd:dt(0,7,8,17,0), evStart:dt(0,7,9,14,0), evEnd:dt(0,7,9,17,0), status:2},
  {id:14, title:'#14 校园电影节', desc:'展映学生原创短片+经典电影回顾。设置最佳导演、最佳演员等6个奖项，颁奖典礼压轴。', org:'电影协会', venue:'学术报告厅', saleStart:dt(0,7,20,10,0), saleEnd:dt(0,8,8,20,0), evStart:dt(0,8,10,13,0), evEnd:dt(0,8,10,20,0), status:0},
  {id:15, title:'#15 中秋游园会', desc:'赏月、猜灯谜、放河灯、品月饼。汉服体验区+传统手工艺展示+民乐演奏。', org:'校学生会 × 国学社', venue:'湖畔广场', saleStart:dt(0,9,1,10,0), saleEnd:dt(0,9,15,18,0), evStart:dt(0,9,15,18,0), evEnd:dt(0,9,15,22,0), status:0},
];

lines.push('-- 15 场活动（含编号）');
lines.push('INSERT INTO `event` (`event_id`, `title`, `description`, `organizer`, `venue`, `sale_start_time`, `sale_end_time`, `event_start_time`, `event_end_time`, `status`) VALUES');
events.forEach((e, i) => {
  const comma = i < events.length - 1 ? ',' : ';';
  lines.push(`(${e.id}, '${e.title}', '${e.desc}', '${e.org}', '${e.venue}', ${e.saleStart}, ${e.saleEnd}, ${e.evStart}, ${e.evEnd}, ${e.status})${comma}`);
});
lines.push('');

// ========== 票档（每活动 2-3 档）==========
const ticketTemplates = [
  [{name:'VIP区', price:288, qty:200, desc:'前排座椅 + 纪念品'},{name:'标准区', price:188, qty:600, desc:'中部区域'},{name:'看台区', price:88, qty:400, desc:'后方看台'}],
  [{name:'前排区', price:128, qty:300, desc:'近距离观赛'},{name:'标准区', price:68, qty:1000, desc:'内场座椅'},{name:'站票区', price:38, qty:700, desc:'后方站立区'}],
  [{name:'VIP区', price:388, qty:100, desc:'前5排+茶歇'},{name:'优选区', price:238, qty:300, desc:'中区'},{name:'普通区', price:128, qty:400, desc:'后区'}],
  [{name:'前排区', price:88, qty:100, desc:'近距离观演'},{name:'标准区', price:48, qty:200, desc:'阶梯式座位'}],
];
// 为活动5-15也生成票档
for (let i = 4; i < 15; i++) {
  const base = ticketTemplates[i % ticketTemplates.length];
  ticketTemplates.push(base.map(t => ({...t, price: t.price + rand(-30, 50), qty: t.qty + rand(-100, 300)})));
}

let tid = 1;
const tvalues = [];
for (let ei = 0; ei < events.length; ei++) {
  const tts = ticketTemplates[ei];
  for (const t of tts) {
    tvalues.push(`(${tid}, ${ei + 1}, '${t.name}', ${Math.max(t.price, 10)}.00, ${Math.max(t.qty, 50)}, ${Math.max(t.qty, 50)}, '${t.desc}')`);
    tid++;
  }
}
lines.push('-- 票档数据');
lines.push(`INSERT INTO ticket_category (ticket_id, event_id, name, price, total_quantity, remaining_quantity, description) VALUES`);
lines.push(tvalues.join(',\n') + ';');
lines.push('');

// ========== 200 条笔记 ==========
const noteContents = [
  '今天天气真好，校园里的樱花开了～',
  '刚看完一场超棒的电影，推荐给大家！',
  '有人一起去图书馆自习吗？',
  '食堂今天的麻辣烫太赞了',
  '期末考试复习中，加油加油！',
  '新入手了一台相机，拍照技术有待提高',
  '周末打算去爬山，有没有一起的？',
  '分享一首最近单曲循环的歌《起风了》',
  '实验室待到凌晨才回宿舍，科研狗的日常',
  '今天跑了5公里，感觉状态不错',
  '想问下有没有人出二手教材？',
  '校园里的猫又生了一窝小猫咪',
  '参加了一个超有意思的讲座，受益匪浅',
  '篮球场约球，3缺2，来的私聊',
  '深夜食堂泡面配火腿肠，满足',
  '推荐一个学习网站，对编程很有帮助',
  '有没有人知道学校附近的打印店几点开门？',
  '终于把论文初稿写完了！',
  '分享一组今天拍的校园风景照',
  '今天的晚霞特别美，可惜手机拍不出来',
  '趁年轻多尝试，别怕失败',
  '学校游泳馆人太多了，得早点去',
  '最近在看《三体》，真的太震撼了',
  '周末约了朋友去吃火锅，期待～',
  '有没有人想一起组队参加数学建模？',
  '实习第一天，紧张又兴奋',
  '分享考研经验：坚持就是胜利',
  '买了一盆多肉放在宿舍窗台',
  '求推荐一款好用的笔记软件',
  '操场今晚有露天电影诶',
  '刚学了做红烧肉，成品还不错',
  '导师今天夸了我，开心一整天',
  '学校快递站队伍排到门口了',
  '哪位同学捡到一个蓝色U盘？里面有重要文件',
  '今天的篮球赛太精彩了',
  '下周要交课程设计了，肝起来',
  '有人知道学校心理中心在哪里吗',
  '健身房新换了一批器材，好评',
  '分享一个PPT模板网站，做展示很有用',
  '校园网又崩了...',
];
const noteLines = [];
for (let i = 0; i < 200; i++) {
  const uid = rand(1, 100);
  const content = pick(noteContents);
  const m = rand(3, 6);
  const d = m === 6 ? rand(1, 21) : rand(1, 28);
  const time = dt(0, m, d, rand(0,23), rand(0,59));
  noteLines.push(`(${uid}, '${content.replace(/'/g, "''")}', ${time})`);
}
lines.push('-- 200 条随机笔记');
lines.push('INSERT INTO `user_note` (`user_id`, `content`, `create_time`) VALUES');
lines.push(noteLines.join(',\n') + ';');
lines.push('');

// ========== 300 条关注关系 ==========
const followSet = new Set();
const followLines = [];
for (let i = 0; i < 300; i++) {
  let f1 = rand(1, 100), f2 = rand(1, 100);
  while (f1 === f2) f2 = rand(1, 100);
  const key = `${f1}-${f2}`;
  if (followSet.has(key)) continue;
  followSet.add(key);
  followLines.push(`(${f1}, ${f2})`);
}
lines.push('-- 随机关注关系');
lines.push('INSERT INTO `user_follow` (`follower_id`, `user_id`) VALUES');
lines.push(followLines.join(',\n') + ';');
lines.push('');

// ========== 500 条点赞 ==========
const likeSet = new Set();
const likeLines = [];
for (let i = 0; i < 500; i++) {
  const nid = rand(1, 200);
  const uid = rand(1, 100);
  const key = `${nid}-${uid}`;
  if (likeSet.has(key)) continue;
  likeSet.add(key);
  likeLines.push(`(${nid}, ${uid})`);
}
lines.push('-- 随机点赞数据');
lines.push('INSERT INTO `note_like` (`note_id`, `user_id`) VALUES');
lines.push(likeLines.join(',\n') + ';');

// 输出
fs.writeFileSync('gen_data.sql', lines.join('\n'), 'utf-8');
console.log(`Generated: ${followSet.size} follows, ${likeSet.size} likes, 100 users, 15 events, ${tid-1} tickets, ${noteLines.length} notes`);
